package io.github.pactproject.api;

import io.github.pactproject.api.exception.BackendException;

public interface BackendTransaction
{
    void apply() throws BackendException;

    void rollback() throws BackendException;
}
