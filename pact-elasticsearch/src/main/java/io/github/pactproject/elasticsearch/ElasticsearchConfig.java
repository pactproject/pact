package io.github.pactproject.elasticsearch;

import java.net.URI;
import java.util.Map;

public record ElasticsearchConfig(
        URI endpoint,
        String username,
        String password,
        String apiKey,
        String principalMode
) {
    public ElasticsearchConfig {
        if (endpoint == null || !endpoint.isAbsolute()
                || !("http".equalsIgnoreCase(endpoint.getScheme())
                || "https".equalsIgnoreCase(endpoint.getScheme()))
                || endpoint.getHost() == null
                || endpoint.getUserInfo() != null
                || endpoint.getQuery() != null
                || endpoint.getFragment() != null) {
            throw new IllegalArgumentException(
                    "Elasticsearch endpoint must be an absolute HTTP(S) URL"
            );
        }
        boolean basic = username != null && !username.isBlank()
                && password != null && !password.isBlank();
        boolean anyBasic = username != null || password != null;
        boolean key = apiKey != null && !apiKey.isBlank();
        if (anyBasic && !basic) {
            throw new IllegalArgumentException(
                    "Elasticsearch username and password must be supplied together"
            );
        }
        if (basic == key) {
            throw new IllegalArgumentException(
                    "Configure exactly one of Elasticsearch basic auth or api-key"
            );
        }
        if (!"require".equals(principalMode)
                && !"ensure".equals(principalMode)) {
            throw new IllegalArgumentException(
                    "Elasticsearch principal-mode must be require or ensure"
            );
        }
    }

    public static ElasticsearchConfig from(Map<String, String> values) {
        String endpoint = require(values, "endpoint");
        String mode = values.getOrDefault("principal-mode", "require");
        return new ElasticsearchConfig(
                URI.create(endpoint),
                values.get("username"),
                values.get("password"),
                values.get("api-key"),
                mode
        );
    }

    private static String require(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Missing required Elasticsearch config: " + key
            );
        }
        return value;
    }

    @Override
    public String toString() {
        return "ElasticsearchConfig[endpoint=" + endpoint
                + ", username=" + username
                + ", authentication=[REDACTED], principalMode="
                + principalMode + "]";
    }
}
