package io.github.pactproject.ranger;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RangerConfigTest {
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
