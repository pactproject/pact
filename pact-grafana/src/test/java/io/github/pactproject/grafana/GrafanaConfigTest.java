package io.github.pactproject.grafana;

import io.github.pactproject.api.BackendFactory;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GrafanaConfigTest {
    @Test void defaultsToAuthoritativeAndDiscoversFactoryWithoutConnecting() throws Exception {
        var config = GrafanaConfig.from(Map.of("endpoint", "https://grafana.example/proxy", "token", "credential-value", "org-id", "2"));
        assertEquals(2, config.organizationId());
        assertEquals(1000, config.pageSize());
        assertEquals(30, config.timeout().toSeconds());
        assertFalse(config.toString().contains("credential-value"));
        assertTrue(config.toString().contains("authoritative"));
        var factory = ServiceLoader.load(BackendFactory.class).stream().filter(provider -> provider.type() == GrafanaBackendFactory.class)
                .findFirst().orElseThrow().get();
        assertEquals("grafana", factory.type());
        try (var backend = factory.create("custom-monitoring", Map.of("endpoint", "http://not-contacted.invalid", "token", "test", "org-id", "1"))) {
            assertEquals("custom-monitoring", backend.id());
        }
    }

    @Test void rejectsUnknownKeysInvalidScopeCredentialsAndTimeouts() {
        for (var override : List.of(Map.of("endpoint", "file:///tmp/test"), Map.of("endpoint", "http://user:pass@localhost"),
                Map.of("endpoint", "http://localhost/?token=x"), Map.of("token", ""), Map.of("token", "a\nb"),
                Map.of("org-id", "0"), Map.of("org-id", "abc"), Map.of("timeout-seconds", "0"),
                Map.of("timeout-seconds", "301"), Map.of("page-size", "5001"), Map.of("page-size", "0"),
                Map.of("reconciliation-mode", "managed"), Map.of("principal-mode", "ensure"))) {
            var values = new HashMap<>(Map.of("endpoint", "http://localhost", "token", "value", "org-id", "1"));
            values.putAll(override);
            assertThrows(IllegalArgumentException.class, () -> GrafanaConfig.from(values), override.toString());
        }
        for (String key : List.of("endpoint", "token", "org-id")) {
            var values = new HashMap<>(Map.of("endpoint", "http://localhost", "token", "value", "org-id", "1"));
            values.remove(key);
            assertThrows(IllegalArgumentException.class, () -> GrafanaConfig.from(values));
        }
        assertThrows(IllegalArgumentException.class, () -> new GrafanaBackendFactory().create("", Map.of()));
    }
}
