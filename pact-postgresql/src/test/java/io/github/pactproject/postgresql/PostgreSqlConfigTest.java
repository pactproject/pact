package io.github.pactproject.postgresql;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PostgreSqlConfigTest {
    @Test
    void readsRequiredConnectionSettings() {
        PostgreSqlConfig config = PostgreSqlConfig.from(Map.of(
                "jdbc-url", "jdbc:postgresql://localhost:5432/postgres",
                "username", "pact_grant_manager",
                "password", "secret"
        ));
        assertEquals("pact_grant_manager", config.username());
    }

    @Test
    void rejectsMissingOrUnsupportedSettings() {
        assertThrows(
                IllegalArgumentException.class,
                () -> PostgreSqlConfig.from(Map.of(
                        "jdbc-url", "jdbc:postgresql://localhost/postgres",
                        "username", "pact"
                ))
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> PostgreSqlConfig.from(Map.of(
                        "jdbc-url", "jdbc:postgresql://localhost/postgres",
                        "username", "pact",
                        "password", "secret",
                        "ssl-mode", "require"
                ))
        );
    }
}
