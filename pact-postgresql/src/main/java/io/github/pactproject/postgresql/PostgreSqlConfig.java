package io.github.pactproject.postgresql;

import java.util.Map;
import java.util.Set;

public record PostgreSqlConfig(
        String jdbcUrl,
        String username,
        String password,
        PostgreSqlReconciliationMode reconciliationMode,
        boolean preserveDefaultPublicPrivileges
) {
    private static final Set<String> SUPPORTED_KEYS =
            Set.of(
                    "jdbc-url",
                    "username",
                    "password",
                    "reconciliation-mode",
                    "preserve-default-public-privileges"
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
                PostgreSqlReconciliationMode.GRANTOR,
                true
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
                parseMode(config.get("reconciliation-mode")),
                parsePreserveDefaultPublicPrivileges(
                        config.get("preserve-default-public-privileges")
                )
        );
    }

    private static PostgreSqlReconciliationMode parseMode(String value) {
        return value == null || value.isBlank()
                ? PostgreSqlReconciliationMode.GRANTOR
                : PostgreSqlReconciliationMode.parse(value);
    }

    private static boolean parsePreserveDefaultPublicPrivileges(String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        throw new IllegalArgumentException(
                "PostgreSQL preserve-default-public-privileges must be true or false"
        );
    }

    private static void requireValue(String value, String key) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Missing required PostgreSQL config: " + key
            );
        }
    }
}
