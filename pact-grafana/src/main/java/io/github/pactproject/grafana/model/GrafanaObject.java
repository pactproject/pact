package io.github.pactproject.grafana.model;

public record GrafanaObject(GrafanaResource resource, String title, String parentUid) {
    public GrafanaObject {
        java.util.Objects.requireNonNull(resource);
        if (title == null || title.isBlank() || parentUid == null) {
            throw new IllegalArgumentException("Grafana catalog title and parent UID are required");
        }
    }
}
