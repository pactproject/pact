package io.github.pactproject.kubernetes.config;

import java.util.Map;
import java.util.Set;

public record KubernetesConfig(
        String group,
        String version,
        String plural)
{
    private static final Set<String> SUPPORTED_KEYS =
            Set.of("group", "version", "plural");

    public KubernetesConfig
    {
        requireValue(group, "group");
        requireValue(version, "version");
        requireValue(plural, "plural");
    }

    public static KubernetesConfig from(
            Map<String, String> config)
    {
        if (!SUPPORTED_KEYS.containsAll(config.keySet())) {
            Set<String> unsupported = new java.util.HashSet<>(
                    config.keySet()
            );
            unsupported.removeAll(SUPPORTED_KEYS);
            throw new IllegalArgumentException(
                    "Unsupported Kubernetes config key(s): "
                            + unsupported
            );
        }

        return new KubernetesConfig(
                configuredValue(
                        config,
                        "group",
                        "DATA_ACCESS_GROUP",
                        "com.example.ru"
                ),
                configuredValue(
                        config,
                        "version",
                        "DATA_ACCESS_VERSION",
                        "v1"
                ),
                configuredValue(
                        config,
                        "plural",
                        "DATA_ACCESS_PLURAL",
                        "dataaccesses"
                )
        );
    }

    private static String configuredValue(
            Map<String, String> config,
            String key,
            String environmentVariable,
            String defaultValue)
    {
        String configured = config.get(key);
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        String environmentValue = System.getenv(environmentVariable);
        return environmentValue == null || environmentValue.isBlank()
                ? defaultValue
                : environmentValue;
    }

    private static void requireValue(String value, String key)
    {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Kubernetes config '" + key + "' must not be blank"
            );
        }
    }
}
