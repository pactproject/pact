package io.github.pactproject.postgresql.model;

public record DefaultPrivilegeScope(
        String database,
        String schema,
        String creator,
        DefaultPrivilegeType type
) {
    public DefaultPrivilegeScope {
        if (database == null || database.isBlank()) {
            throw new IllegalArgumentException(
                    "PostgreSQL default privilege database must not be blank"
            );
        }
        if (schema == null || schema.isBlank()) {
            throw new IllegalArgumentException(
                    "PostgreSQL default privilege schema must not be blank"
            );
        }
        if (creator == null || creator.isBlank()) {
            throw new IllegalArgumentException(
                    "PostgreSQL default privilege creator must not be blank"
            );
        }
        if (type == null) {
            throw new IllegalArgumentException(
                    "PostgreSQL default privilege type is required"
            );
        }
    }
}
