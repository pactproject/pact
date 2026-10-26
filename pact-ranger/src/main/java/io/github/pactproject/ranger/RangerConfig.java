package io.github.pactproject.ranger;

import java.net.URI;
import java.util.Map;

public record RangerConfig(
        URI baseUrl,
        String username,
        String password,
        String serviceName,
        int pageSize,
        boolean managedOnly
) {
    public RangerConfig(
            URI baseUrl,
            String username,
            String password,
            String serviceName,
            int pageSize
    ) {
        this(baseUrl, username, password, serviceName, pageSize, false);
    }

    public static RangerConfig from(Map<String, String> config) {
        var unsupported = new java.util.TreeSet<>(config.keySet());
        unsupported.removeAll(java.util.Set.of("base-url", "username", "password", "service-name", "page-size", "managed-only"));
        if (!unsupported.isEmpty()) throw new IllegalArgumentException("Unsupported Ranger config key(s): " + unsupported);
        URI baseUrl = URI.create(require(config, "base-url"));
        if (!baseUrl.isAbsolute()
                || !("http".equalsIgnoreCase(baseUrl.getScheme())
                || "https".equalsIgnoreCase(baseUrl.getScheme()))) {
            throw new IllegalArgumentException(
                    "Ranger base-url must be an absolute HTTP(S) URL"
            );
        }

        int pageSize;
        try {
            pageSize = Integer.parseInt(config.getOrDefault("page-size", "100"));
        }
        catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Ranger page-size must be a positive integer",
                    e
            );
        }
        if (pageSize < 1) {
            throw new IllegalArgumentException(
                    "Ranger page-size must be a positive integer"
            );
        }
        boolean managedOnly = parseBoolean(
                config.getOrDefault("managed-only", "false")
        );

        return new RangerConfig(
                baseUrl,
                require(config, "username"),
                require(config, "password"),
                require(config, "service-name"),
                pageSize,
                managedOnly
        );
    }

    private static boolean parseBoolean(String value) {
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        throw new IllegalArgumentException(
                "Ranger managed-only must be true or false"
        );
    }

    private static String require(Map<String, String> config, String key) {
        String value = config.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Missing required Ranger config: " + key
            );
        }
        return value;
    }

    @Override public String toString() {
        return "RangerConfig[baseUrl=" + io.github.pactproject.api.DiagnosticSanitizer.redact(String.valueOf(baseUrl), password)
                + ", username=" + username + ", password=[REDACTED], serviceName=" + serviceName
                + ", pageSize=" + pageSize + ", managedOnly=" + managedOnly + "]";
    }
}
