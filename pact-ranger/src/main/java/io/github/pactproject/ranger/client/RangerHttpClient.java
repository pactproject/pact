package io.github.pactproject.ranger.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.pactproject.ranger.RangerConfig;
import io.github.pactproject.ranger.api.RangerClient;
import io.github.pactproject.ranger.api.RangerClientException;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public final class RangerHttpClient implements RangerClient {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int GENERATED_PASSWORD_LENGTH = 24;
    private static final String PASSWORD_UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String PASSWORD_LOWER = "abcdefghijklmnopqrstuvwxyz";
    private static final String PASSWORD_DIGITS = "0123456789";
    private static final String PASSWORD_SPECIAL = "!@#$%^&*_-+=";
    private static final String PASSWORD_ALL = PASSWORD_UPPER
            + PASSWORD_LOWER
            + PASSWORD_DIGITS
            + PASSWORD_SPECIAL;

    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final URI baseUrl;
    private final String authorization;

    public RangerHttpClient(RangerConfig config) {
        this(HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .build(),
                new ObjectMapper(),
                config);
    }

    @Override
    public void ensureUser(String username)
            throws RangerClientException {
        try {
            request(
                    "GET",
                    "xusers/users/userName/" + encode(username),
                    null
            );
        }
        catch (RangerClientException e) {
            if (e.statusCode() != 400) {
                throw e;
            }
            ObjectNode user = mapper.createObjectNode();
            user.put("name", username);
            user.put("password", generatePassword());
            user.put("status", 1);
            user.put("isVisible", 1);
            request("POST", "xusers/users", user);
        }
    }

    public RangerHttpClient(
            HttpClient httpClient,
            ObjectMapper mapper,
            RangerConfig config
    ) {
        this.httpClient = httpClient;
        this.mapper = mapper;
        String url = config.baseUrl().toString();
        this.baseUrl = URI.create(url.endsWith("/") ? url : url + "/");
        String credentials = config.username() + ":" + config.password();
        this.authorization = "Basic " + Base64.getEncoder().encodeToString(
                credentials.getBytes(StandardCharsets.UTF_8)
        );
    }

    @Override
    public String getServiceType(String serviceName)
            throws RangerClientException {
        JsonNode service = request(
                "GET",
                "public/v2/api/service/name/" + encode(serviceName),
                null
        );
        JsonNode type = service.get("type");
        if (type == null || !type.isTextual() || type.asText().isBlank()) {
            throw new RangerClientException(
                    "Ranger service '" + serviceName
                            + "' response has no service type"
            );
        }
        return type.asText();
    }

    @Override
    public JsonNode getServiceDefinition(String serviceType)
            throws RangerClientException {
        return request(
                "GET",
                "plugins/definitions/name/" + encode(serviceType),
                null
        );
    }

    @Override
    public List<JsonNode> getPolicies(String serviceName, int pageSize)
            throws RangerClientException {
        List<JsonNode> result = new ArrayList<>();
        int startIndex = 0;
        while (true) {
            JsonNode response = request(
                    "GET",
                    "public/v2/api/service/" + encode(serviceName)
                            + "/policy?startIndex=" + startIndex
                            + "&pageSize=" + pageSize,
                    null
            );
            JsonNode policies = response.isArray()
                    ? response
                    : response.path("policies").isArray()
                            ? response.path("policies")
                            : response.path("list").isArray()
                                    ? response.path("list")
                                    : null;
            if (policies == null) {
                throw new RangerClientException(
                        "Ranger returned an invalid policy list response"
                );
            }
            policies.forEach(result::add);
            int totalCount = response.path("totalCount").asInt(-1);
            startIndex += policies.size();
            if (policies.isEmpty()
                    || policies.size() < pageSize
                    || (totalCount >= 0 && startIndex >= totalCount)) {
                return List.copyOf(result);
            }
        }
    }

    @Override
    public JsonNode createPolicy(ObjectNode policy)
            throws RangerClientException {
        return request(
                "POST",
                "public/v2/api/policy",
                policy
        );
    }

    @Override
    public JsonNode updatePolicy(long id, ObjectNode policy)
            throws RangerClientException {
        ObjectNode body = policy.deepCopy();
        body.put("id", id);
        return request(
                "PUT",
                "public/v2/api/policy/" + id,
                body
        );
    }

    @Override
    public void deletePolicy(long id)
            throws RangerClientException {
        request("DELETE", "public/v2/api/policy/" + id, null);
    }

    private JsonNode request(
            String method,
            String path,
            ObjectNode body
    ) throws RangerClientException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                        baseUrl.resolve(path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", authorization)
                .header("Accept", "application/json");
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        else {
            try {
                builder.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(
                                mapper.writeValueAsString(body)
                        ));
            }
            catch (JsonProcessingException e) {
                throw new RangerClientException(
                        "Failed to serialize Ranger request",
                        e
                );
            }
        }

        try {
            HttpResponse<String> response = httpClient.send(
                    builder.build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw new RangerClientException(
                        "Ranger API " + method + " " + path
                                + " returned HTTP " + status
                                + (response.body().isBlank()
                                ? ""
                                : ": " + response.body()),
                        status
                );
            }
            if (response.body().isBlank()) {
                return mapper.createObjectNode();
            }
            return mapper.readTree(response.body());
        }
        catch (IOException e) {
            throw new RangerClientException(
                    "Ranger API request failed: " + method + " " + path,
                    e
            );
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RangerClientException(
                    "Interrupted during Ranger API request: "
                            + method + " " + path,
                    e
            );
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    private static String generatePassword() {
        char[] password = new char[GENERATED_PASSWORD_LENGTH];
        password[0] = randomChar(PASSWORD_UPPER);
        password[1] = randomChar(PASSWORD_LOWER);
        password[2] = randomChar(PASSWORD_DIGITS);
        password[3] = randomChar(PASSWORD_SPECIAL);

        for (int index = 4; index < password.length; index++) {
            password[index] = randomChar(PASSWORD_ALL);
        }
        for (int index = password.length - 1; index > 0; index--) {
            int swapIndex = SECURE_RANDOM.nextInt(index + 1);
            char current = password[index];
            password[index] = password[swapIndex];
            password[swapIndex] = current;
        }
        return new String(password);
    }

    private static char randomChar(String alphabet) {
        return alphabet.charAt(SECURE_RANDOM.nextInt(alphabet.length()));
    }
}
