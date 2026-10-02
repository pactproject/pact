package io.github.pactproject.postgresql;

import java.util.Map;
import java.util.Set;

public record PostgreSqlConfig(
        String jdbcUrl,
        String username,
        String password
) {
    private static final Set<String> SUPPORTED_KEYS =
            Set.of("jdbc-url", "username", "password");

    public PostgreSqlConfig {
        requireValue(jdbcUrl, "jdbc-url");
        requireValue(username, "username");
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
                config.get("password")
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
