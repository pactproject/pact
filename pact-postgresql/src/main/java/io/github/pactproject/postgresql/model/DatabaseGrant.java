package io.github.pactproject.postgresql.model;

public record DatabaseGrant(
        String database,
        String role,
        DatabasePrivilege privilege
) {
    public DatabaseGrant {
        if (database == null || database.isBlank()) {
            throw new IllegalArgumentException("Database name must not be blank");
        }
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("PostgreSQL role must not be blank");
        }
        if (privilege == null) {
            throw new IllegalArgumentException("Database privilege is required");
        }
    }
}
