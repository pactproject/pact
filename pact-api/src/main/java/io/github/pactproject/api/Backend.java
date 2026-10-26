package io.github.pactproject.api;

import io.github.pactproject.api.exception.BackendException;
import io.github.pactproject.api.exception.BackendFeatureNotImplementedException;

import java.util.Set;

public interface Backend extends AutoCloseable {
    /** Releases persistent client resources. Stateless backends need no cleanup. */
    @Override default void close() throws BackendException { }

    String id();

    void apply(PactState state) throws BackendException;

    /** Read-only capability validation. Identity support must be explicitly implemented. */
    default void validateIdentities(Set<Identity> identities) throws BackendException {
        if (!identities.isEmpty()) {
            throw new BackendFeatureNotImplementedException(id(), "identities");
        }
    }

    /**
     * Creates a compensating transaction for one backend's state transition.
     * Preparation returns a transaction and may validate declarations, resolve
     * references and calculate a plan, but must not mutate external state.
     * Successful preparation does not guarantee successful application:
     * external state may change and writes may fail partway through.
     * Core controls application and calls rollback only for transactions whose apply has started,
     * including the transaction whose apply failed.
     *
     * <p>The default rollback reapplies {@code previousState}. Backends may
     * override preparation to retain cleanup scope or optimize restoration,
     * while preserving the semantics of applying the previous agreed state.
     * Document non-compensatable effects such as account creation or password
     * changes explicitly.</p>
     */
    default BackendTransaction prepareTransaction(
            PactState previousState,
            PactState desiredState
    ) throws BackendException {
        validateIdentities(desiredState.identities());
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

    /**
     * Prepares a transition after failed compensation. Recovery states contain only
     * secret-free declarations whose scopes may still have external side effects.
     * They extend cleanup scope; desiredState remains the sole application target,
     * and previousState remains the rollback target. Preparation is still read-only.
     * Plugins must explicitly support recovery rather than silently discard scope.
     */
    default BackendTransaction prepareTransaction(PactState previousState, PactState desiredState,
            Set<PactState> recoveryStates) throws BackendException {
        if (!recoveryStates.isEmpty()) {
            throw new BackendFeatureNotImplementedException(id(), "recovery-scopes");
        }
        return prepareTransaction(previousState, desiredState);
    }
}
