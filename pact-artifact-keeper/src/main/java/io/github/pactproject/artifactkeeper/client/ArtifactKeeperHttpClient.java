package io.github.pactproject.artifactkeeper.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.pactproject.artifactkeeper.ArtifactKeeperApiProfile;
import io.github.pactproject.artifactkeeper.ArtifactKeeperConfig;
import io.github.pactproject.artifactkeeper.api.ArtifactKeeperApiPermission;
import io.github.pactproject.artifactkeeper.api.ArtifactKeeperApiRepository;
import io.github.pactproject.artifactkeeper.api.ArtifactKeeperApiUser;
import io.github.pactproject.artifactkeeper.api.ArtifactKeeperApiNamedObject;
import io.github.pactproject.artifactkeeper.api.ArtifactKeeperClient;
import io.github.pactproject.api.value.*;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;

public final class ArtifactKeeperHttpClient
        implements ArtifactKeeperClient {

    private static final int DEFAULT_PER_PAGE = 100;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final URI baseUri;
    private final String token;
    private final int perPage;
    private final RateLimitPolicy rateLimit;

    public ArtifactKeeperHttpClient(
            String baseUrl,
            String token
    ) {
        this(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
                new ObjectMapper(),
                baseUrl,
                token,
                DEFAULT_PER_PAGE
        );
    }

    public ArtifactKeeperHttpClient(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            String baseUrl,
            String token,
            int perPage
    ) {
        this(httpClient, objectMapper, baseUrl, token, perPage, ArtifactKeeperApiProfile.CURRENT);
    }

    public ArtifactKeeperHttpClient(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            String baseUrl,
            String token,
            int perPage,
            ArtifactKeeperApiProfile apiProfile
    ) {
        this(httpClient, objectMapper, baseUrl, token, perPage, apiProfile, new RateLimitPolicy());
    }

    ArtifactKeeperHttpClient(HttpClient httpClient, ObjectMapper objectMapper, String baseUrl, String token,
            int perPage, ArtifactKeeperApiProfile apiProfile, RateLimitPolicy rateLimit) {
        if (perPage <= 0) {
            throw new IllegalArgumentException(
                    "perPage must be greater than zero"
            );
        }

        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.baseUri = URI.create(
                baseUrl.endsWith("/")
                        ? baseUrl
                        : baseUrl + "/"
        );
        this.token = token;
        this.perPage = perPage;
        this.rateLimit = Objects.requireNonNull(rateLimit);
        Objects.requireNonNull(apiProfile);
    }

    public ArtifactKeeperHttpClient(ArtifactKeeperConfig artifactKeeperConfig) {
        this(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
                new ObjectMapper(),
                artifactKeeperConfig.url().toString(),
                artifactKeeperConfig.token(),
                artifactKeeperConfig.perPage() > 0
                        ? artifactKeeperConfig.perPage()
                        : DEFAULT_PER_PAGE,
                artifactKeeperConfig.apiProfile()
        );
    }

    @Override
    public List<ArtifactKeeperApiUser> getUsers() throws ArtifactKeeperClientException {
        return getAllPages(
                "/api/v1/users",
                node -> new ArtifactKeeperApiUser(
                        text(node, "id"),
                        text(node, "username"),
                        serviceAccountFlag(node)
                )
        );
    }

    @Override
    public List<ArtifactKeeperApiRepository> getRepositories() throws ArtifactKeeperClientException {
        return getAllPages(
                "/api/v1/repositories",
                node -> new ArtifactKeeperApiRepository(
                        text(node, "id"),
                        text(node, "key"),
                        node.path("name").asText(text(node, "key"))
                )
        );
    }

    @Override
    public List<ArtifactKeeperApiNamedObject> getGroups() throws ArtifactKeeperClientException {
        return getAllPages("/api/v1/groups", node -> new ArtifactKeeperApiNamedObject(
                text(node, "id"), text(node, "name")));
    }

    @Override
    public List<ArtifactKeeperApiNamedObject> getProjects() throws ArtifactKeeperClientException {
        JsonNode response = request("GET", "/api/v1/projects", null, null);
        if (response == null || !response.path("items").isArray()) {
            throw new ArtifactKeeperClientException("Artifact Keeper project list is missing an 'items' array");
        }
        List<ArtifactKeeperApiNamedObject> projects = new ArrayList<>();
        var seen = new HashSet<String>();
        for (JsonNode node : response.path("items")) {
            if (!seen.add(text(node, "id"))) throw new ArtifactKeeperClientException("Duplicate Artifact Keeper project ID in catalog");
            projects.add(new ArtifactKeeperApiNamedObject(text(node, "id"), text(node, "key"), text(node, "name")));
        }
        var pagination = response.path("pagination");
        var pages = pagination.path("total_pages");
        if ((!pagination.isMissingNode() && !pagination.isObject())
                || (!pages.isMissingNode() && (!pages.isIntegralNumber() || !pages.canConvertToInt()
                || pages.asInt() < 0 || pages.asInt() > 1 || (pages.asInt() == 0 && !projects.isEmpty())))
                || (pagination.has("total") && (!pagination.path("total").isIntegralNumber()
                || !pagination.path("total").canConvertToLong()
                || pagination.path("total").asLong() != projects.size()))) {
            throw new ArtifactKeeperClientException("Artifact Keeper returned an incomplete unpaged project catalog");
        }
        return List.copyOf(projects);
    }

    @Override
    public String getArtifactId(String repository, String path, String version) throws ArtifactKeeperClientException {
        String encodedPath = java.util.Arrays.stream(path.split("/", -1))
                .map(segment -> urlEncode(segment).replace("+", "%20"))
                .collect(java.util.stream.Collectors.joining("/"));
        JsonNode response = request("GET", "/api/v1/repositories/" + urlEncode(repository)
                + "/artifacts/" + encodedPath,
                version == null ? null : List.of("version=" + urlEncode(version)), null);
        if (response == null) throw new ArtifactKeeperClientException("Artifact Keeper returned no artifact metadata");
        if (version != null && !version.trim().equals("latest")) {
            String selector = version.trim();
            long revision = response.path("revision").asLong(-1);
            boolean matches = selector.equals(response.path("version_label").asText(null));
            try {
                matches |= Long.parseLong(selector) == revision;
            } catch (NumberFormatException ignored) {
                // A non-numeric selector is a version label.
            }
            if (revision < 1 || !matches) {
                throw new ArtifactKeeperClientException("Artifact Keeper did not resolve requested artifact revision: " + version);
            }
        }
        return text(response, "id");
    }

    @Override
    public List<ArtifactKeeperApiPermission> getPermissions() throws ArtifactKeeperClientException {
        return getAllPages(
                "/api/v1/permissions",
                this::toPermission
        );
    }

    @Override
    public ArtifactKeeperApiPermission createPermission(
            String principalType,
            String principalId,
            String targetType,
            String targetId,
            Set<String> actions
    ) throws ArtifactKeeperClientException {
        return createPermission(principalType, principalId, targetType, targetId, actions, Map.of());
    }

    @Override
    public ArtifactKeeperApiPermission createPermission(
            String principalType, String principalId, String targetType, String targetId,
            Set<String> actions, Map<String, Value> conditions) throws ArtifactKeeperClientException {
        ObjectNode body = objectMapper.createObjectNode();

        body.put("principal_type", principalType);
        body.put("principal_id", principalId);
        body.put("target_type", targetType);
        body.put("target_id", targetId);
        body.set(
                "actions",
                objectMapper.valueToTree(actions)
        );
        if (!conditions.isEmpty()) {
            body.set("conditions", conditionNode(Value.object(conditions)));
        }

        JsonNode response = request(
                "POST",
                "/api/v1/permissions",
                null,
                body
        );

        if (response == null) {
            throw new ArtifactKeeperClientException(
                    "Artifact Keeper API returned no response for permission creation"
            );
        }

        return toPermission(response);
    }

    @Override
    public void deletePermission(String permissionId) throws ArtifactKeeperClientException {
        request(
                "DELETE",
                "/api/v1/permissions/"
                        + urlEncode(permissionId),
                null,
                null
        );
    }

    @Override
    public ArtifactKeeperApiUser createServiceAccount(
            String name
    ) throws ArtifactKeeperClientException {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("name", name);

        JsonNode response = request(
                "POST",
                "/api/v1/service-accounts",
                null,
                body
        );

        if (response == null) {
            throw new ArtifactKeeperClientException(
                    "Artifact Keeper API returned no response for service account creation"
            );
        }

        return new ArtifactKeeperApiUser(
                text(response, "id"),
                text(response, "username"),
                true
        );
    }

    private static boolean serviceAccountFlag(JsonNode node)
            throws ArtifactKeeperClientException {
        JsonNode flag = node.path("is_service_account");
        if (!flag.isBoolean()) {
            throw new ArtifactKeeperClientException(
                    "Artifact Keeper user response is missing boolean 'is_service_account'"
            );
        }
        return flag.booleanValue();
    }

    private <T> List<T> getAllPages(
            String path,
            NodeMapper<T> mapper
    ) throws ArtifactKeeperClientException {
        List<T> result = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        Integer expectedPages = null;
        Long expectedTotal = null;

        for (int page = 1; ; page++) {
            JsonNode response = request(
                    "GET",
                    path,
                    List.of(
                            "page=" + page,
                            "per_page=" + perPage
                    ),
                    null
            );

            if (response == null) {
                throw new ArtifactKeeperClientException(
                        "Artifact Keeper API returned no response for " +
                                path + " page " + page
                );
            }

            JsonNode items = response.path("items");
            if (!items.isArray()) {
                throw new ArtifactKeeperClientException(
                        "Artifact Keeper API response does not contain " +
                                "an 'items' array"
                );
            }

            for (JsonNode item : items) {
                String itemId = text(item, "id");
                if (itemId.isBlank() || !seenIds.add(itemId)) {
                    throw new ArtifactKeeperClientException("Artifact Keeper repeated a catalog ID at " + path + " page " + page);
                }
                result.add(mapper.map(item));
            }

            int totalPages = response
                    .path("pagination")
                    .path("total_pages")
                    .asInt(-1);

            if (totalPages < 0 || !response.path("pagination").path("total_pages").isIntegralNumber()
                    || !response.path("pagination").path("total_pages").canConvertToInt()) {
                throw new ArtifactKeeperClientException(
                        "Artifact Keeper API response does not contain " +
                                "'pagination.total_pages'"
                );
            }

            JsonNode totalNode = response.path("pagination").path("total");
            Long total = totalNode.isMissingNode() ? null : totalNode.asLong(-1);
            if (total != null && (!totalNode.isIntegralNumber() || !totalNode.canConvertToLong() || total < 0)) {
                throw new ArtifactKeeperClientException("Artifact Keeper catalog has invalid total: " + path);
            }
            if (page == 1) { expectedPages = totalPages; expectedTotal = total; }
            else if (totalPages != expectedPages || !Objects.equals(total, expectedTotal)) {
                throw new ArtifactKeeperClientException("Artifact Keeper catalog pagination changed during read: " + path);
            }
            if ((totalPages == 0 && !items.isEmpty()) || (page < totalPages && items.isEmpty())
                    || (total != null && result.size() > total)) {
                throw new ArtifactKeeperClientException("Artifact Keeper catalog is incomplete or inconsistent: " + path);
            }

            if (page >= totalPages) {
                if (expectedTotal != null && result.size() != expectedTotal) {
                    throw new ArtifactKeeperClientException("Artifact Keeper catalog total does not match loaded items: " + path);
                }
                return List.copyOf(result);
            }
        }
    }

    private ArtifactKeeperApiPermission toPermission(
            JsonNode node
    ) throws ArtifactKeeperClientException {
        JsonNode actionsNode = node.path("actions");

        if (!actionsNode.isArray()) {
            throw new ArtifactKeeperClientException(
                    "Artifact Keeper permission 'actions' is not an array"
            );
        }

        Set<String> actions = new java.util.HashSet<>();

        for (JsonNode action : actionsNode) {
            if (!action.isTextual()) {
                throw new ArtifactKeeperClientException(
                        "Artifact Keeper permission action is not a string"
                );
            }

            actions.add(action.asText());
        }

        return new ArtifactKeeperApiPermission(
                text(node, "id"),
                text(node, "principal_type"),
                text(node, "principal_id"),
                text(node, "target_type"),
                text(node, "target_id"),
                actions, readConditions(node.path("conditions"))
        );
    }

    private Map<String, Value> readConditions(JsonNode node) throws ArtifactKeeperClientException {
        if (node.isMissingNode() || node.isNull()) return Map.of();
        if (!(conditionValue(node) instanceof ObjectValue object)) {
            throw new ArtifactKeeperClientException("Artifact Keeper conditions must be an object");
        }
        return Map.copyOf(object.values());
    }

    private Value conditionValue(JsonNode node) throws ArtifactKeeperClientException {
        if (node.isTextual()) return Value.string(node.textValue());
        if (node.isBoolean()) return Value.bool(node.booleanValue());
        if (node.isNumber()) return Value.number(node.decimalValue());
        if (node.isArray()) {
            Set<Value> values = new HashSet<>();
            for (JsonNode item : node) values.add(conditionValue(item));
            return Value.set(values);
        }
        if (node.isObject()) {
            Map<String, Value> values = new HashMap<>();
            var fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                values.put(field.getKey(), conditionValue(field.getValue()));
            }
            return Value.object(values);
        }
        throw new ArtifactKeeperClientException("Unsupported Artifact Keeper condition value: " + node);
    }

    private JsonNode conditionNode(Value value) {
        return switch (value) {
            case StringValue string -> objectMapper.valueToTree(string.value());
            case BooleanValue bool -> objectMapper.valueToTree(bool.value());
            case NumberValue number -> objectMapper.valueToTree(number.value());
            case SetValue set -> {
                var array = objectMapper.createArrayNode();
                set.values().forEach(item -> array.add(conditionNode(item)));
                yield array;
            }
            case ObjectValue object -> {
                var node = objectMapper.createObjectNode();
                object.values().forEach((key, item) -> node.set(key, conditionNode(item)));
                yield node;
            }
        };
    }

    private JsonNode request(
            String method,
            String path,
            List<String> query,
            JsonNode body
    ) throws ArtifactKeeperClientException {
        URI uri = buildUri(path, query);

        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .method(
                        method,
                        body == null
                                ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofString(
                                writeJson(body)
                        )
                );

        if (body != null) {
            request.header(
                    "Content-Type",
                    "application/json"
            );
        }

        HttpResponse<String> response = sendWithRateLimitRetry(
                request.build()
        );

        String text = response.body();
        String[] secrets = {token, body == null ? null : body.path("password").asText(null)};

        if (response.statusCode() < 200
                || response.statusCode() >= 300) {

            throw new ArtifactKeeperClientException(
                    "Artifact Keeper API " + method + " " + path
                            + " returned HTTP " + response.statusCode() + ": "
                            + io.github.pactproject.api.DiagnosticSanitizer.redact(text, secrets)
            );
        }

        if (response.statusCode() == 204
                || text.isEmpty()) {
            return null;
        }

        try {
            return objectMapper.readTree(text);
        } catch (JsonProcessingException e) {
            throw new ArtifactKeeperClientException(
                    "Artifact Keeper API returned invalid JSON " +
                            "(" + response.statusCode() + ") " + method + " " + path + ": "
                            + io.github.pactproject.api.DiagnosticSanitizer.redact(e.getMessage(), secrets)
            );
        }
    }

    private HttpResponse<String> sendWithRateLimitRetry(
            HttpRequest request
    ) throws ArtifactKeeperClientException {
        long started = rateLimit.start();
        for (int attempt = 0; ; attempt++) {
            try {
                HttpResponse<String> response =
                        httpClient.send(
                                HttpRequest.newBuilder(request, (name, value) -> true)
                                        .timeout(rateLimit.remaining(started)).build(),
                                HttpResponse.BodyHandlers.ofString()
                        );

                if (response.statusCode() != 429
                        || attempt >= RateLimitPolicy.MAX_RETRIES) {
                    return response;
                }

                rateLimit.pause(started, rateLimit.delayMillis(response.headers()));

            } catch (IOException e) {
                throw new ArtifactKeeperClientException(
                        "Artifact Keeper API request failed: " + request.uri() + ": "
                                + e.getClass().getSimpleName() + ": "
                                + io.github.pactproject.api.DiagnosticSanitizer.redact(e.getMessage(), token)
                );
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();

                throw new ArtifactKeeperClientException(
                        "Artifact Keeper API request interrupted: "
                                + request.uri(),
                        e
                );
            }
        }
    }

    private URI buildUri(
            String path,
            List<String> query
    ) {
        StringBuilder value = new StringBuilder(
                baseUri.resolve(
                        path.startsWith("/")
                                ? path.substring(1)
                                : path
                ).toString()
        );

        if (query != null && !query.isEmpty()) {
            value.append("?");

            value.append(String.join("&", query));
        }

        return URI.create(value.toString());
    }

    private String writeJson(JsonNode body) throws ArtifactKeeperClientException {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new ArtifactKeeperClientException(
                    "Failed to serialize Artifact Keeper API request"
            );
        }
    }

    private static String text(
            JsonNode node,
            String field
    ) throws ArtifactKeeperClientException {
        JsonNode value = node.get(field);

        if (value == null || !value.isTextual()) {
            throw new ArtifactKeeperClientException(
                    "Artifact Keeper API response is missing " +
                            "string field '" + field + "'"
            );
        }

        return value.asText();
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(
                value,
                StandardCharsets.UTF_8
        );
    }

    @FunctionalInterface
    private interface NodeMapper<T> {
        T map(JsonNode node) throws ArtifactKeeperClientException;
    }
}
