package io.github.pactproject.app.config;

import java.util.Map;

public record BackendConfig(
        String id,
        String type,
        Map<String, String> config
) {
    public BackendConfig {
        config = Map.copyOf(config);
    }
}
