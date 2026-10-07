package io.github.pactproject.postgresql;

import java.util.Locale;

public enum PostgreSqlReconciliationMode {
    GRANTOR,
    AUTHORITATIVE;

    public static PostgreSqlReconciliationMode parse(String value) {
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unsupported PostgreSQL reconciliation-mode: " + value,
                    e
            );
        }
    }
}
