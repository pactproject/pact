package io.github.pactproject.postgresql.model;

import java.util.Optional;

public enum GrantLevel {
    DATABASE("database", null),
    SCHEMA("schema", DATABASE),
    TABLE("table", SCHEMA),
    COLUMN("column", TABLE),
    SEQUENCE("sequence", SCHEMA),
    FUNCTION("function", SCHEMA),
    PROCEDURE("procedure", SCHEMA);

    private final String key;
    private final GrantLevel parent;

    GrantLevel(String key, GrantLevel parent) {
        this.key = key;
        this.parent = parent;
    }

    public String key() {
        return key;
    }

    public GrantLevel parent() {
        return parent;
    }

    public boolean isAncestorOf(GrantLevel descendant) {
        for (GrantLevel current = descendant; current != null;
                current = current.parent) {
            if (this == current) {
                return true;
            }
        }
        return false;
    }

    public static Optional<GrantLevel> fromKey(String key) {
        for (GrantLevel level : values()) {
            if (level.key.equals(key)) {
                return Optional.of(level);
            }
        }
        return Optional.empty();
    }
}
