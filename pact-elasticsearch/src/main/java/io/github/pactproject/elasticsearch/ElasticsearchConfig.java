package io.github.pactproject.elasticsearch;

import java.net.URI;
import java.util.Map;

public record ElasticsearchConfig(
        URI endpoint,
        String username,
        String password,
        String apiKey,
        String principalMode,
        ElasticsearchReconciliationMode reconciliationMode,
        java.nio.file.Path caCertificate
) {
    public ElasticsearchConfig {
        java.util.Objects.requireNonNull(reconciliationMode, "Elasticsearch reconciliation mode is required");
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
        if (apiKey != null && apiKey.isBlank()) {
            throw new IllegalArgumentException("Elasticsearch api-key must not be blank");
        }
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
        if (caCertificate != null && !"https".equalsIgnoreCase(endpoint.getScheme())) {
            throw new IllegalArgumentException("Elasticsearch ca-certificate requires an HTTPS endpoint");
        }
    }

    public ElasticsearchConfig(URI endpoint, String username, String password, String apiKey, String principalMode) {
        this(endpoint, username, password, apiKey, principalMode, ElasticsearchReconciliationMode.AUTHORITATIVE, null);
    }

    public static ElasticsearchConfig from(Map<String, String> values) {
        var unsupported = new java.util.HashSet<>(values.keySet());
        unsupported.removeAll(java.util.Set.of("endpoint", "username", "password", "api-key", "principal-mode",
                "reconciliation-mode", "ca-certificate"));
        if (!unsupported.isEmpty()) throw new IllegalArgumentException("Unsupported Elasticsearch config key(s): " + unsupported);
        String endpoint = require(values, "endpoint");
        String mode = values.getOrDefault("principal-mode", "require");
        return new ElasticsearchConfig(
                URI.create(endpoint),
                values.get("username"),
                values.get("password"),
                values.get("api-key"),
                mode,
                ElasticsearchReconciliationMode.parse(values.getOrDefault("reconciliation-mode", "authoritative")),
                values.containsKey("ca-certificate")
                        ? java.nio.file.Path.of(require(values, "ca-certificate")) : null
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
                + principalMode + ", reconciliationMode=" + reconciliationMode + ", caCertificate=" + caCertificate + "]";
    }
}
