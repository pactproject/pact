package io.github.pactproject.app.config;

import java.util.List;

public record PactConfig(
        StateProviderConfig stateProvider,
        List<BackendConfig> backends
) {
    public PactConfig {
        backends = List.copyOf(backends);
    }
}
