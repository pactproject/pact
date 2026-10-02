package io.github.pactproject.kubernetes.config;

import java.util.List;
import java.util.Map;
import java.util.Set;

public record KubernetesConfig(
        String group,
        String version,
        String plural,
        long informerResyncMillis,
        long informerStartTimeoutSeconds,
        List<Long> statusRetryDelaysMillis)
{
    private static final long DEFAULT_INFORMER_RESYNC_MILLIS = 5_000L;
    private static final long DEFAULT_INFORMER_START_TIMEOUT_SECONDS = 30L;
    private static final List<Long> DEFAULT_STATUS_RETRY_DELAYS_MILLIS =
            List.of(100L, 200L, 400L);
    private static final Set<String> SUPPORTED_KEYS =
            Set.of(
                    "group",
                    "version",
                    "plural",
                    "informer-resync-millis",
                    "informer-start-timeout-seconds",
                    "status-retry-delays-millis"
            );

    public KubernetesConfig
    {
        requireValue(group, "group");
        requireValue(version, "version");
        requireValue(plural, "plural");
        requirePositive(informerResyncMillis, "informer-resync-millis");
        requirePositive(
                informerStartTimeoutSeconds,
                "informer-start-timeout-seconds"
        );
        statusRetryDelaysMillis = List.copyOf(statusRetryDelaysMillis);
        if (statusRetryDelaysMillis.stream().anyMatch(delay -> delay <= 0)) {
            throw new IllegalArgumentException(
                    "Kubernetes config 'status-retry-delays-millis' "
                            + "must contain only positive integers"
            );
        }
    }

    public KubernetesConfig(
            String group,
            String version,
            String plural)
    {
        this(
                group,
                version,
                plural,
                DEFAULT_INFORMER_RESYNC_MILLIS,
                DEFAULT_INFORMER_START_TIMEOUT_SECONDS,
                DEFAULT_STATUS_RETRY_DELAYS_MILLIS
        );
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
                ),
                configuredLong(
                        config,
                        "informer-resync-millis",
                        DEFAULT_INFORMER_RESYNC_MILLIS
                ),
                configuredLong(
                        config,
                        "informer-start-timeout-seconds",
                        DEFAULT_INFORMER_START_TIMEOUT_SECONDS
                ),
                configuredLongList(
                        config,
                        "status-retry-delays-millis",
                        DEFAULT_STATUS_RETRY_DELAYS_MILLIS
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

    private static long configuredLong(
            Map<String, String> config,
            String key,
            long defaultValue)
    {
        String value = config.get(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value);
        }
        catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Kubernetes config '" + key + "' must be an integer",
                    e
            );
        }
    }

    private static List<Long> configuredLongList(
            Map<String, String> config,
            String key,
            List<Long> defaultValue)
    {
        String value = config.get(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return java.util.Arrays.stream(value.split(",", -1))
                    .map(String::trim)
                    .map(Long::parseLong)
                    .toList();
        }
        catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Kubernetes config '" + key
                            + "' must be a comma-separated list of integers",
                    e
            );
        }
    }

    private static void requirePositive(long value, String key)
    {
        if (value <= 0) {
            throw new IllegalArgumentException(
                    "Kubernetes config '" + key
                            + "' must be a positive integer"
            );
        }
    }
}
