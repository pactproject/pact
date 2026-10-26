package io.github.pactproject.ranger;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RangerConfigTest {
    @org.junit.jupiter.api.Test
    void rejectsUnknownKeysAndRetainsNonSecretDiagnosticConfiguration() {
        var values = new java.util.HashMap<>(java.util.Map.of("base-url", "http://localhost:6080/service",
                "username", "sync", "password", "hidden-password", "service-name", "analytics"));
        var text = RangerConfig.from(values).toString();
        org.junit.jupiter.api.Assertions.assertFalse(text.contains("hidden-password"));
        org.junit.jupiter.api.Assertions.assertTrue(text.contains("analytics") && text.contains("sync"));
        values.put("managed-onyl", "true");
        var error = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> RangerConfig.from(values));
        org.junit.jupiter.api.Assertions.assertTrue(error.getMessage().contains("managed-onyl"));
    }

    @org.junit.jupiter.api.Test
    void discoversRegisteredBackendFactory() {
        var factory = java.util.ServiceLoader.load(io.github.pactproject.api.BackendFactory.class)
                .stream().filter(provider -> provider.type().equals(RangerBackendFactory.class))
                .findFirst().orElseThrow().get();
        org.junit.jupiter.api.Assertions.assertEquals("ranger", factory.type());
    }

    @Test
    void resolvesConfigurationFromNamedRangerServiceWithoutConfiguredType() {
        RangerConfig config = RangerConfig.from(Map.of(
                "base-url", "https://ranger.example/service",
                "username", "pact",
                "password", "secret",
                "service-name", "kafka-prod"
        ));

        assertEquals(
                URI.create("https://ranger.example/service"),
                config.baseUrl()
        );
        assertEquals("kafka-prod", config.serviceName());
        assertEquals(100, config.pageSize());
        assertFalse(config.managedOnly());
    }

    @Test
    void enablesManagedOnlyPolicyScopeWhenConfigured() {
        RangerConfig config = RangerConfig.from(Map.of(
                "base-url", "https://ranger.example/service",
                "username", "pact",
                "password", "secret",
                "service-name", "kafka-prod",
                "managed-only", "true"
        ));

        assertTrue(config.managedOnly());
    }

    @Test
    void rejectsInvalidManagedOnlyConfiguration() {
        assertThrows(
                IllegalArgumentException.class,
                () -> RangerConfig.from(Map.of(
                        "base-url", "https://ranger.example/service",
                        "username", "pact",
                        "password", "secret",
                        "service-name", "kafka-prod",
                        "managed-only", "sometimes"
                ))
        );
    }

    @Test
    void requiresNamedRangerService() {
        assertThrows(
                IllegalArgumentException.class,
                () -> RangerConfig.from(Map.of(
                        "base-url", "http://ranger.example/service",
                        "username", "pact",
                        "password", "secret"
                ))
        );
    }
}
