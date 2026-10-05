package io.github.pactproject.postgresql.model;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

public enum Privilege {
    CONNECT(GrantLevel.DATABASE),
    TEMPORARY(GrantLevel.DATABASE),
    CREATE(GrantLevel.DATABASE, GrantLevel.SCHEMA),
    USAGE(GrantLevel.SCHEMA, GrantLevel.SEQUENCE),
    SELECT(GrantLevel.TABLE, GrantLevel.COLUMN, GrantLevel.SEQUENCE),
    INSERT(GrantLevel.TABLE, GrantLevel.COLUMN),
    UPDATE(GrantLevel.TABLE, GrantLevel.COLUMN, GrantLevel.SEQUENCE),
    REFERENCES(GrantLevel.TABLE, GrantLevel.COLUMN),
    DELETE(GrantLevel.TABLE),
    TRUNCATE(GrantLevel.TABLE),
    TRIGGER(GrantLevel.TABLE),
    EXECUTE(GrantLevel.FUNCTION, GrantLevel.PROCEDURE);

    private final Set<GrantLevel> levels;

    Privilege(GrantLevel first, GrantLevel... rest) {
        this.levels = EnumSet.of(first, rest);
    }

    public boolean appliesTo(GrantLevel level) {
        return levels.contains(level);
    }

    public static Privilege parse(GrantLevel level, String value) {
        String normalized = value.toUpperCase(Locale.ROOT);
        if (normalized.equals("TEMP")) {
            normalized = "TEMPORARY";
        }
        Privilege privilege;
        try {
            privilege = Privilege.valueOf(normalized);
        }
        catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unsupported PostgreSQL " + level.key()
                            + " privilege: " + value,
                    e
            );
        }
        if (!privilege.appliesTo(level)) {
            throw new IllegalArgumentException(
                    "Unsupported PostgreSQL " + level.key()
                            + " privilege: " + value
            );
        }
        return privilege;
    }
}
