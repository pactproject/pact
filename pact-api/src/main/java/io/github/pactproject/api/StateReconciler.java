package io.github.pactproject.api;

import io.github.pactproject.api.exception.StateReconciliationException;

public interface StateReconciler
{
    void restoreAppliedState(PactState state)
            throws StateReconciliationException;

    void apply(PactState desiredState)
            throws StateReconciliationException;
}
