package io.github.pactproject.elasticsearch;

public enum ElasticsearchReconciliationMode {
    MANAGED, AUTHORITATIVE;

    public static ElasticsearchReconciliationMode parse(String value) {
        return switch (value) {
            case "managed" -> MANAGED;
            case "authoritative" -> AUTHORITATIVE;
            default -> throw new IllegalArgumentException("Elasticsearch reconciliation-mode must be managed or authoritative");
        };
    }
}
