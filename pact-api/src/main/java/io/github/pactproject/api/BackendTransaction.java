package io.github.pactproject.api;

import io.github.pactproject.api.exception.BackendException;

/** A prepared backend transition whose application and compensation are coordinated by core. */
public interface BackendTransaction
{
    void apply() throws BackendException;

    /**
     * Restores the previous agreed declarative state within the transition's
     * scope, with the semantics of reapplying that state. Implementations may
     * optimize the operations but must retain cleanup of partially applied
     * changes, subject to documented non-compensatable effects.
     *
     * <p>Core calls this only after {@link #apply()} has started, including
     * when it failed partway through or a successful write response was lost.</p>
     */
    void rollback() throws BackendException;
}
