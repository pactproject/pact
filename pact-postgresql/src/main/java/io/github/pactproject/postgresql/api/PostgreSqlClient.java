package io.github.pactproject.postgresql.api;

import io.github.pactproject.postgresql.model.DatabaseGrant;

import java.util.Set;

public interface PostgreSqlClient {
    Set<DatabaseGrant> getManagedGrants(Set<String> databases)
            throws PostgreSqlClientException;

    void synchronize(Set<String> databases, Set<DatabaseGrant> desired)
            throws PostgreSqlClientException;
}
