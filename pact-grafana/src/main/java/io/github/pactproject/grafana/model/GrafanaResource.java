package io.github.pactproject.grafana.model;

/** Canonical identity; titles and paths deliberately do not participate in equality. */
public record GrafanaResource(Kind kind, String uid) implements Comparable<GrafanaResource> {
    public enum Kind { FOLDER, DASHBOARD }

    public GrafanaResource {
        java.util.Objects.requireNonNull(kind);
        if (uid == null || uid.isBlank()) throw new IllegalArgumentException("Grafana UID must not be blank");
    }

    @Override public int compareTo(GrafanaResource other) {
        int order = kind.compareTo(other.kind);
        return order != 0 ? order : uid.compareTo(other.uid);
    }
}
