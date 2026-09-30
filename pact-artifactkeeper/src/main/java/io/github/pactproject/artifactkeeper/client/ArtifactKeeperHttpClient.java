package io.github.pactproject.artifactkeeper.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.pactproject.artifactkeeper.ArtifactKeeperConfig;
import io.github.pactproject.artifactkeeper.api.ArtifactKeeperApiPermission;
import io.github.pactproject.artifactkeeper.api.ArtifactKeeperApiRepository;
import io.github.pactproject.artifactkeeper.api.ArtifactKeeperApiUser;
import io.github.pactproject.artifactkeeper.api.ArtifactKeeperClient;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class ArtifactKeeperHttpClient
        implements ArtifactKeeperClient {

    private static final int DEFAULT_PER_PAGE = 100;
    private static final int MAX_RATE_LIMIT_RETRIES = 3;
    private static final long DEFAULT_RATE_LIMIT_DELAY_MILLIS = 1000;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final URI baseUri;
    private final String token;
    private final int perPage;

    public ArtifactKeeperHttpClient(
            String baseUrl,
            String token
    ) {
        this(
                HttpClient.newHttpClient(),
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
    }

    public ArtifactKeeperHttpClient(ArtifactKeeperConfig artifactKeeperConfig) {
        this(
                HttpClient.newHttpClient(),
                new ObjectMapper(),
                artifactKeeperConfig.url().toString(),
                artifactKeeperConfig.token(),
                artifactKeeperConfig.perPage() > 0
                        ? artifactKeeperConfig.perPage()
                        : DEFAULT_PER_PAGE
        );
    }

    @Override
    public List<ArtifactKeeperApiUser> getUsers() throws ArtifactKeeperClientException {
        return getAllPages(
                "/api/v1/users",
                node -> new ArtifactKeeperApiUser(
                        text(node, "id"),
                        text(node, "username")
                )
        );
    }

    @Override
    public List<ArtifactKeeperApiRepository> getRepositories() throws ArtifactKeeperClientException {
        return getAllPages(
                "/api/v1/repositories",
                node -> new ArtifactKeeperApiRepository(
                        text(node, "id"),
                        text(node, "key")
                )
        );
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
        ObjectNode body = objectMapper.createObjectNode();

        body.put("principal_type", principalType);
        body.put("principal_id", principalId);
        body.put("target_type", targetType);
        body.put("target_id", targetId);
        body.set(
                "actions",
                objectMapper.valueToTree(actions)
        );

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
                text(response, "username")
        );
    }

    private <T> List<T> getAllPages(
            String path,
            NodeMapper<T> mapper
    ) throws ArtifactKeeperClientException {
        List<T> result = new ArrayList<>();

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
                result.add(mapper.map(item));
            }

            int totalPages = response
                    .path("pagination")
                    .path("total_pages")
                    .asInt(-1);

            if (totalPages < 0) {
                throw new ArtifactKeeperClientException(
                        "Artifact Keeper API response does not contain " +
                                "'pagination.total_pages'"
                );
            }

            if (page >= totalPages) {
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
                actions
        );
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

        if (response.statusCode() < 200
                || response.statusCode() >= 300) {

            throw new ArtifactKeeperClientException(
                    "Artifact Keeper API request failed (" +
                            response.statusCode() + " " +
                            response.version() + ") " +
                            uri + ": " +
                            parseErrorBody(text)
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
                            "(" + response.statusCode() + ") " +
                            uri + ": " + text,
                    e
            );
        }
    }

    private HttpResponse<String> sendWithRateLimitRetry(
            HttpRequest request
    ) throws ArtifactKeeperClientException {
        for (int attempt = 0; ; attempt++) {
            try {
                HttpResponse<String> response =
                        httpClient.send(
                                request,
                                HttpResponse.BodyHandlers.ofString()
                        );

                if (response.statusCode() != 429
                        || attempt >= MAX_RATE_LIMIT_RETRIES) {
                    return response;
                }

                sleep(getRateLimitDelay(response));

            } catch (IOException e) {
                throw new ArtifactKeeperClientException(
                        "Artifact Keeper API request failed: "
                                + request.uri(),
                        e
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

    private long getRateLimitDelay(
            HttpResponse<?> response
    ) {
        String retryAfter =
                response.headers()
                        .firstValue("Retry-After")
                        .orElse(null);

        if (retryAfter != null) {
            try {
                long seconds = Long.parseLong(retryAfter);

                return Math.max(
                        0,
                        seconds * 1000
                );
            } catch (NumberFormatException ignored) {
                try {
                    Instant retryAt =
                            Instant.parse(retryAfter);

                    return Math.max(
                            0,
                            Duration.between(
                                    Instant.now(),
                                    retryAt
                            ).toMillis()
                    );
                } catch (Exception ignoredAgain) {
                    // fall through
                }
            }
        }

        String reset =
                response.headers()
                        .firstValue("X-RateLimit-Reset")
                        .orElse(null);

        if (reset != null) {
            try {
                long epochSeconds = Long.parseLong(reset);

                return Math.max(
                        0,
                        epochSeconds * 1000
                                - System.currentTimeMillis()
                );
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }

        return DEFAULT_RATE_LIMIT_DELAY_MILLIS;
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
                    "Failed to serialize Artifact Keeper API request",
                    e
            );
        }
    }

    private String parseErrorBody(String body) {
        if (body == null || body.isEmpty()) {
            return "<empty response body>";
        }

        try {
            JsonNode node = objectMapper.readTree(body);

            JsonNode message = node.get("message");

            if (message != null && message.isTextual()) {
                return message.asText();
            }

            return node.toString();
        } catch (JsonProcessingException e) {
            return body;
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

    private static void sleep(long millis) throws ArtifactKeeperClientException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            throw new ArtifactKeeperClientException(
                    "Interrupted while waiting for Artifact Keeper rate limit",
                    e
            );
        }
    }

    @FunctionalInterface
    private interface NodeMapper<T> {
        T map(JsonNode node) throws ArtifactKeeperClientException;
    }
}
