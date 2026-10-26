package io.github.pactproject.postgresql;

import java.util.Map;
import java.util.Set;

public record PostgreSqlConfig(
        String jdbcUrl,
        String username,
        String password,
        PostgreSqlReconciliationMode reconciliationMode
) {
    private static final Set<String> SUPPORTED_KEYS =
            Set.of(
                    "jdbc-url",
                    "username",
                    "password",
                    "reconciliation-mode"
            );

    public PostgreSqlConfig {
        requireValue(jdbcUrl, "jdbc-url");
        requireValue(username, "username");
        if (reconciliationMode == null) {
            throw new IllegalArgumentException(
                    "PostgreSQL reconciliation mode is required"
            );
        }
        if (password == null) {
            throw new IllegalArgumentException(
                    "Missing required PostgreSQL config: password"
            );
        }
        if (!jdbcUrl.startsWith("jdbc:postgresql:")) {
            throw new IllegalArgumentException(
                    "PostgreSQL jdbc-url must use the jdbc:postgresql scheme"
            );
        }
    }

    public PostgreSqlConfig(String jdbcUrl, String username, String password) {
        this(
                jdbcUrl,
                username,
                password,
                PostgreSqlReconciliationMode.AUTHORITATIVE
        );
    }

    public static PostgreSqlConfig from(Map<String, String> config) {
        Set<String> unsupported = new java.util.HashSet<>(config.keySet());
        unsupported.removeAll(SUPPORTED_KEYS);
        if (!unsupported.isEmpty()) {
            throw new IllegalArgumentException(
                    "Unsupported PostgreSQL config key(s): " + unsupported
            );
        }
        return new PostgreSqlConfig(
                config.get("jdbc-url"),
                config.get("username"),
                config.get("password"),
                parseMode(config.get("reconciliation-mode"))
        );
    }

    private static PostgreSqlReconciliationMode parseMode(String value) {
        return value == null || value.isBlank()
                ? PostgreSqlReconciliationMode.AUTHORITATIVE
                : PostgreSqlReconciliationMode.parse(value);
    }

    private static void requireValue(String value, String key) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Missing required PostgreSQL config: " + key
            );
        }
    }

    @Override public String toString() {
        return "PostgreSqlConfig[jdbcUrl=" + io.github.pactproject.api.DiagnosticSanitizer.redact(jdbcUrl, password)
                + ", username=" + username + ", password=[REDACTED], reconciliationMode=" + reconciliationMode + "]";
    }
}
