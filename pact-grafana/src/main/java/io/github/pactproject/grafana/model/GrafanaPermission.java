package io.github.pactproject.grafana.model;

/** A direct ACL entry. Inherited entries are filtered at the protocol boundary. */
public record GrafanaPermission(Subject subject, String identifier, int permission)
        implements Comparable<GrafanaPermission> {
    public enum Subject { USER, TEAM, ROLE }

    public GrafanaPermission {
        java.util.Objects.requireNonNull(subject);
        if (permission != 1 && permission != 2 && permission != 4) {
            throw new IllegalArgumentException("Unknown Grafana permission level: " + permission);
        }
        if (subject == Subject.ROLE) {
            if (!"Viewer".equals(identifier) && !"Editor".equals(identifier)) {
                throw new IllegalArgumentException("Invalid Grafana ACL role");
            }
        } else {
            try {
                if (Long.parseLong(identifier) <= 0) throw new IllegalArgumentException("Invalid Grafana ACL subject ID");
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid Grafana ACL subject ID");
            }
        }
    }

    public static GrafanaPermission user(long id, int permission) {
        return new GrafanaPermission(Subject.USER, Long.toString(id), permission);
    }

    @Override public int compareTo(GrafanaPermission other) {
        int order = subject.compareTo(other.subject);
        if (order == 0) order = identifier.compareTo(other.identifier);
        return order == 0 ? Integer.compare(permission, other.permission) : order;
    }
}
