package io.github.pactproject.app.config;

import java.util.Map;

public record StateProviderConfig(
        String type,
        Map<String, String> config
) {
    public StateProviderConfig {
        config = Map.copyOf(config);
    }
}
