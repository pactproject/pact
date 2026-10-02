package io.github.pactproject.postgresql.model;

import java.util.Locale;

public enum DatabasePrivilege {
    CONNECT,
    CREATE,
    TEMPORARY;

    public static DatabasePrivilege parse(String value) {
        String normalized = switch (value.toUpperCase(Locale.ROOT)) {
            case "TEMP" -> "TEMPORARY";
            default -> value.toUpperCase(Locale.ROOT);
        };
        try {
            return DatabasePrivilege.valueOf(normalized);
        }
        catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unsupported PostgreSQL database privilege: " + value,
                    e
            );
        }
    }
}
