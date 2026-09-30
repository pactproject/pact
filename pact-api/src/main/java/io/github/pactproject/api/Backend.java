package io.github.pactproject.api;

import io.github.pactproject.api.exception.BackendException;

public interface Backend {
    String id();

    void apply(PactState state) throws BackendException;
}
