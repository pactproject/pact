package io.github.pactproject.kubernetes.config;

import org.junit.jupiter.api.Test;

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
}
