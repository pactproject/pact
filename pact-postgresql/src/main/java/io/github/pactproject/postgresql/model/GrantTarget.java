package io.github.pactproject.postgresql.model;

/**
 * A path or set of branches in the PostgreSQL object hierarchy. Deeper fields
 * are {@code null} when the target stops at a higher level.
 */
public record GrantTarget(
        String database,
        String schema,
        String table,
        String column,
        String sequence,
        RoutineSignature function,
        RoutineSignature procedure
) {
    public GrantTarget(
            String database,
            String schema,
            String table,
            String column
    ) {
        this(database, schema, table, column, null, null, null);
    }

    public GrantTarget(
            String database,
            String schema,
            String table,
            String column,
            String sequence
    ) {
        this(database, schema, table, column, sequence, null, null);
    }

    public GrantTarget(
            String database,
            String schema,
            String table,
            String column,
            String sequence,
            RoutineSignature function
    ) {
        this(database, schema, table, column, sequence, function, null);
    }

    public GrantTarget {
        requireName(database, "database");
        if (schema == null
                && (table != null || column != null || sequence != null
                || function != null || procedure != null)) {
            throw new IllegalArgumentException(
                    "PostgreSQL object target requires a schema"
            );
        }
        if (table == null && column != null) {
            throw new IllegalArgumentException(
                    "PostgreSQL column target requires a table"
            );
        }
        if (schema != null) {
            requireName(schema, "schema");
        }
        if (table != null) {
            requireName(table, "table");
        }
        if (column != null) {
            requireName(column, "column");
        }
        if (sequence != null) {
            requireName(sequence, "sequence");
        }
    }

    /**
     * Returns the leaf level for a single grant target. Compiled targets
     * contain only one object branch, although a resource may name both.
     */
    public GrantLevel level() {
        if (column != null) {
            return GrantLevel.COLUMN;
        }
        if (sequence != null) {
            return GrantLevel.SEQUENCE;
        }
        if (function != null) {
            return GrantLevel.FUNCTION;
        }
        if (procedure != null) {
            return GrantLevel.PROCEDURE;
        }
        if (table != null) {
            return GrantLevel.TABLE;
        }
        if (schema != null) {
            return GrantLevel.SCHEMA;
        }
        return GrantLevel.DATABASE;
    }

    public boolean contains(GrantLevel level) {
        return switch (level) {
            case DATABASE -> database != null;
            case SCHEMA -> schema != null;
            case TABLE -> table != null;
            case COLUMN -> column != null;
            case SEQUENCE -> sequence != null;
            case FUNCTION -> function != null;
            case PROCEDURE -> procedure != null;
        };
    }

    public GrantTarget pathTo(GrantLevel level) {
        if (!contains(level)) {
            throw new IllegalArgumentException(
                    "PostgreSQL '" + level.key()
                            + "' permissions require a "
                            + level.key() + " in the resource target"
            );
        }
        return new GrantTarget(
                database,
                GrantLevel.SCHEMA.isAncestorOf(level) ? schema : null,
                GrantLevel.TABLE.isAncestorOf(level) ? table : null,
                GrantLevel.COLUMN.isAncestorOf(level) ? column : null,
                GrantLevel.SEQUENCE.isAncestorOf(level) ? sequence : null,
                GrantLevel.FUNCTION.isAncestorOf(level) ? function : null,
                GrantLevel.PROCEDURE.isAncestorOf(level) ? procedure : null
        );
    }

    private static void requireName(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "PostgreSQL " + field + " target must not be blank"
            );
        }
        if ("*".equals(value)) {
            throw new IllegalArgumentException(
                    "PostgreSQL wildcard targets are not supported; use "
                            + "defaultPrivileges for schema-wide access"
            );
        }
    }
}
