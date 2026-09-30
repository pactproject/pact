package io.github.pactproject.app;

import io.github.pactproject.api.Backend;
import io.github.pactproject.api.StateProvider;

import java.util.List;

public record PactComponents(
        StateProvider stateProvider,
        List<Backend> backends
) {
    public PactComponents {
        backends = List.copyOf(backends);
    }
}
