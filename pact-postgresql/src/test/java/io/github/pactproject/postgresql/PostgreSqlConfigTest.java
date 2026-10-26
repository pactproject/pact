package io.github.pactproject.postgresql;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PostgreSqlConfigTest {
    @org.junit.jupiter.api.Test
    void retainsConnectionDetailsButMasksPasswordsInDiagnosticConfiguration() {
        var config = new PostgreSqlConfig("jdbc:postgresql://host/analytics?password=uri-secret", "sync", "hidden-password");
        var text = config.toString();
        org.junit.jupiter.api.Assertions.assertFalse(text.contains("hidden-password") || text.contains("uri-secret"));
        org.junit.jupiter.api.Assertions.assertTrue(text.contains("analytics") && text.contains("sync"));
    }

    @org.junit.jupiter.api.Test
    void discoversRegisteredBackendFactory() {
        var factory = java.util.ServiceLoader.load(io.github.pactproject.api.BackendFactory.class)
                .stream().filter(provider -> provider.type().equals(PostgreSqlBackendFactory.class))
                .findFirst().orElseThrow().get();
        org.junit.jupiter.api.Assertions.assertEquals("postgresql", factory.type());
    }

    @Test
    void readsRequiredConnectionSettings() {
        PostgreSqlConfig config = PostgreSqlConfig.from(Map.of(
                "jdbc-url", "jdbc:postgresql://localhost:5432/postgres",
                "username", "pact_grant_manager",
                "password", "secret"
        ));
        assertEquals("pact_grant_manager", config.username());
        assertEquals(
                PostgreSqlReconciliationMode.AUTHORITATIVE,
                config.reconciliationMode()
        );
        assertEquals(PostgreSqlReconciliationMode.AUTHORITATIVE,
                new PostgreSqlConfig("jdbc:postgresql://localhost/postgres", "pact", "secret").reconciliationMode());
    }

    @Test
    void readsExplicitGrantorCompatibilityMode() {
        PostgreSqlConfig config = PostgreSqlConfig.from(Map.of(
                "jdbc-url", "jdbc:postgresql://localhost:5432/postgres",
                "username", "postgres",
                "password", "secret",
                "reconciliation-mode", "grantor"
        ));
        assertEquals(
                PostgreSqlReconciliationMode.GRANTOR,
                config.reconciliationMode()
        );
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
                        "preserve-default-public-privileges", "true"
                ))
        );
    }
}
