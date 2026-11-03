package io.github.pactproject.postgresql.model;

/** An explicit owner constraint on one concrete object; absence means unmanaged. */
public record Ownership(GrantTarget target, String role) {
    public Ownership {
        if (target == null || target.level() == GrantLevel.COLUMN) {
            throw new IllegalArgumentException("PostgreSQL columns cannot have a separate owner");
        }
        if (role == null || role.isBlank() || "PUBLIC".equals(role)) {
            throw new IllegalArgumentException("PostgreSQL ownership requires a concrete role, not PUBLIC");
        }
    }
}
