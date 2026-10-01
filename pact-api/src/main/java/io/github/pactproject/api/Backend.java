package io.github.pactproject.api;

import io.github.pactproject.api.exception.BackendException;

public interface Backend {
    String id();

    void apply(PactState state) throws BackendException;

    /**
     * Creates a compensating transaction for one backend's state transition.
     * Backends with snapshot-based preparation can override this method.
     */
    default BackendTransaction prepare(
            PactState previousState,
            PactState desiredState
    ) throws BackendException {
        return new BackendTransaction() {
            @Override
            public void apply() throws BackendException {
                Backend.this.apply(desiredState);
            }

            @Override
            public void rollback() throws BackendException {
                Backend.this.apply(previousState);
            }
        };
    }
}
