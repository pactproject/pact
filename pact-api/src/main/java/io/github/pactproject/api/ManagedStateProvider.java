package io.github.pactproject.api;

import io.github.pactproject.api.exception.StateProviderException;

public interface ManagedStateProvider
        extends StateProvider,
        AutoCloseable
{
    void start(StateReconciler reconciler)
            throws StateProviderException;

    @Override
    void close();
}
