package io.github.pactproject.elasticsearch.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pactproject.elasticsearch.ElasticsearchConfig;
import io.github.pactproject.elasticsearch.api.ElasticsearchClient;
import io.github.pactproject.elasticsearch.api.ElasticsearchClientException;

import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

public final class ElasticsearchHttpClient implements ElasticsearchClient {
    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final String authorization;
    private final String baseUrl;

    public ElasticsearchHttpClient(ElasticsearchConfig config) {
        this(config, HttpClient.newHttpClient(), new ObjectMapper());
    }

    ElasticsearchHttpClient(
            ElasticsearchConfig config,
            HttpClient httpClient,
            ObjectMapper mapper
    ) {
        this.httpClient = httpClient;
        this.mapper = mapper;
        this.authorization = config.apiKey() != null
                ? "ApiKey " + config.apiKey()
                : "Basic " + Base64.getEncoder().encodeToString(
                        (config.username() + ":" + config.password())
                                .getBytes(StandardCharsets.UTF_8)
                );
        String endpoint = config.endpoint().toString();
        this.baseUrl = endpoint.endsWith("/")
                ? endpoint.substring(0, endpoint.length() - 1)
                : endpoint;
    }

    @Override
    public JsonNode getRole(String name) throws ElasticsearchClientException {
        return get("/_security/role/" + segment(name), name);
    }

    @Override
    public JsonNode getUser(String username)
            throws ElasticsearchClientException {
        return get("/_security/user/" + segment(username), username);
    }

    @Override
    public void putRole(String name, JsonNode definition)
            throws ElasticsearchClientException {
        send("PUT", "/_security/role/" + segment(name), definition);
    }

    @Override
    public void deleteRole(String name)
            throws ElasticsearchClientException {
        send("DELETE", "/_security/role/" + segment(name), null);
    }

    @Override
    public void putUser(String username, JsonNode definition)
            throws ElasticsearchClientException {
        send("PUT", "/_security/user/" + segment(username), definition);
    }

    private JsonNode get(String path, String name)
            throws ElasticsearchClientException {
        HttpResponse<String> response = request("GET", path, null);
        if (response.statusCode() == 404) {
            return null;
        }
        checkStatus(response, "GET " + path);
        try {
            JsonNode result = mapper.readTree(response.body()).get(name);
            return result == null || result.isNull()
                    ? null
                    : result.deepCopy();
        }
        catch (IOException e) {
            throw new ElasticsearchClientException(
                    "Invalid Elasticsearch response for " + path, e
            );
        }
    }

    private void send(
            String method,
            String path,
            JsonNode body
    ) throws ElasticsearchClientException {
        HttpResponse<String> response = request(method, path, body);
        checkStatus(response, method + " " + path);
    }

    private HttpResponse<String> request(
            String method,
            String path,
            JsonNode body
    ) throws ElasticsearchClientException {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(java.net.URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", authorization)
                    .header("Accept", "application/json");
            if (body == null) {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            }
            else {
                builder.header("Content-Type", "application/json")
                        .method(
                                method,
                                HttpRequest.BodyPublishers.ofString(
                                        mapper.writeValueAsString(body)
                                )
                        );
            }
            return httpClient.send(
                    builder.build(),
                    HttpResponse.BodyHandlers.ofString()
            );
        }
        catch (IOException e) {
            throw new ElasticsearchClientException(
                    "Elasticsearch request failed: " + method + " " + path,
                    e
            );
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ElasticsearchClientException(
                    "Elasticsearch request interrupted: " + method + " "
                            + path,
                    e
            );
        }
    }

    private static String segment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("%21", "!")
                .replace("%27", "'")
                .replace("%28", "(")
                .replace("%29", ")");
    }

    private static void checkStatus(
            HttpResponse<String> response,
            String operation
    ) throws ElasticsearchClientException {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ElasticsearchClientException(
                    operation + " returned HTTP " + response.statusCode()
            );
        }
    }
}
