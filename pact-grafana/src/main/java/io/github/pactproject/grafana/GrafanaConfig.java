package io.github.pactproject.grafana;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

public record GrafanaConfig(URI endpoint, String token, long organizationId, Duration timeout, int pageSize) {
    public GrafanaConfig {
        if (endpoint == null || !endpoint.isAbsolute() || endpoint.getHost() == null
                || !("http".equalsIgnoreCase(endpoint.getScheme()) || "https".equalsIgnoreCase(endpoint.getScheme()))
                || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null) {
            throw new IllegalArgumentException("Grafana endpoint must be an absolute HTTP(S) URL without credentials, query or fragment");
        }
        if (token == null || token.isBlank() || token.contains("\r") || token.contains("\n")) {
            throw new IllegalArgumentException("Grafana token must be non-blank and contain no line breaks");
        }
        if (organizationId <= 0) throw new IllegalArgumentException("Grafana org-id must be positive");
        if (timeout == null || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("Grafana timeout-seconds must be between 1 and 300");
        }
        if (pageSize < 1 || pageSize > 5000) throw new IllegalArgumentException("Grafana page-size must be between 1 and 5000");
    }

    public static GrafanaConfig from(Map<String, String> values) {
        if (!Set.of("endpoint", "token", "org-id", "timeout-seconds", "page-size", "reconciliation-mode").containsAll(values.keySet())) {
            throw new IllegalArgumentException("Unsupported Grafana config keys: " + new java.util.TreeSet<>(values.keySet().stream()
                    .filter(key -> !Set.of("endpoint", "token", "org-id", "timeout-seconds", "page-size", "reconciliation-mode").contains(key)).toList()));
        }
        if (!"authoritative".equals(values.getOrDefault("reconciliation-mode", "authoritative"))) {
            throw new IllegalArgumentException("Grafana reconciliation-mode supports authoritative only");
        }
        try {
            return new GrafanaConfig(URI.create(required(values, "endpoint")), required(values, "token"),
                    Long.parseLong(required(values, "org-id")),
                    Duration.ofSeconds(Long.parseLong(values.getOrDefault("timeout-seconds", "30"))),
                    Integer.parseInt(values.getOrDefault("page-size", "1000")));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Grafana org-id, timeout-seconds and page-size must be integers");
        }
    }

    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing required Grafana config: " + key);
        return value;
    }

    @Override public String toString() {
        return "GrafanaConfig[endpoint=" + endpoint + ", token=[REDACTED], organizationId=" + organizationId
                + ", timeout=" + timeout + ", pageSize=" + pageSize + ", reconciliationMode=authoritative]";
    }
}
