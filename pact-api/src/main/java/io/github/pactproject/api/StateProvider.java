package io.github.pactproject.api;

import io.github.pactproject.api.exception.StateProviderException;

public interface StateProvider
{
    PactState load() throws StateProviderException;
}
