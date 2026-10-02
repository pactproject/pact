package io.github.pactproject.kubernetes.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KubernetesConfigTest
{
    @Test
    void readsConfiguredResourceCoordinates()
    {
        assertEquals(
                new KubernetesConfig(
                        "pact.example.org",
                        "v2",
                        "dataaccesses"
                ),
                KubernetesConfig.from(
                        Map.of(
                                "group", "pact.example.org",
                                "version", "v2",
                                "plural", "dataaccesses"
                        )
                )
        );
    }

    @Test
    void rejectsUnknownConfigurationKeys()
    {
        assertThrows(
                IllegalArgumentException.class,
                () -> KubernetesConfig.from(
                        Map.of("namespace", "default")
                )
        );
    }

    @Test
    void rejectsBlankCoordinates()
    {
        assertThrows(
                IllegalArgumentException.class,
                () -> new KubernetesConfig(
                        "com.example.ru",
                        " ",
                        "dataaccesses"
                )
        );
    }

    @Test
    void readsControllerTimingSettings()
    {
        assertEquals(
                new KubernetesConfig(
                        "com.example.ru",
                        "v1",
                        "dataaccesses",
                        1_000L,
                        45L,
                        List.of(50L, 100L)
                ),
                KubernetesConfig.from(Map.of(
                        "informer-resync-millis", "1000",
                        "informer-start-timeout-seconds", "45",
                        "status-retry-delays-millis", "50, 100"
                ))
        );
    }

    @Test
    void rejectsInvalidControllerTimingSettings()
    {
        assertThrows(
                IllegalArgumentException.class,
                () -> KubernetesConfig.from(
                        Map.of("informer-resync-millis", "0")
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> KubernetesConfig.from(
                        Map.of("status-retry-delays-millis", "100,nope")
                )
        );
    }
}
