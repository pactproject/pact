package io.github.pactproject.postgresql;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgreSqlConfigTest {
    @Test
    void readsRequiredConnectionSettings() {
        PostgreSqlConfig config = PostgreSqlConfig.from(Map.of(
                "jdbc-url", "jdbc:postgresql://localhost:5432/postgres",
                "username", "pact_grant_manager",
                "password", "secret"
        ));
        assertEquals("pact_grant_manager", config.username());
        assertEquals(
                PostgreSqlReconciliationMode.GRANTOR,
                config.reconciliationMode()
        );
        assertTrue(config.preserveDefaultPublicPrivileges());
    }

    @Test
    void readsAuthoritativeModeAndPublicDefaultOverride() {
        PostgreSqlConfig config = PostgreSqlConfig.from(Map.of(
                "jdbc-url", "jdbc:postgresql://localhost:5432/postgres",
                "username", "postgres",
                "password", "secret",
                "reconciliation-mode", "authoritative",
                "preserve-default-public-privileges", "false"
        ));
        assertEquals(
                PostgreSqlReconciliationMode.AUTHORITATIVE,
                config.reconciliationMode()
        );
        assertFalse(config.preserveDefaultPublicPrivileges());
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
        assertThrows(
                IllegalArgumentException.class,
                () -> PostgreSqlConfig.from(Map.of(
                        "jdbc-url", "jdbc:postgresql://localhost/postgres",
                        "username", "pact",
                        "password", "secret",
                        "reconciliation-mode", "unknown"
                ))
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> PostgreSqlConfig.from(Map.of(
                        "jdbc-url", "jdbc:postgresql://localhost/postgres",
                        "username", "pact",
                        "password", "secret",
                        "preserve-default-public-privileges", "sometimes"
                ))
        );
    }
}
