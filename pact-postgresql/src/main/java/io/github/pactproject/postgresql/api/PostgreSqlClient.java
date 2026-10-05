package io.github.pactproject.postgresql.api;

import io.github.pactproject.postgresql.model.Grant;
import io.github.pactproject.api.Identity;

import java.util.Set;

public interface PostgreSqlClient {
    Set<Grant> getManagedGrants(Set<String> databases)
            throws PostgreSqlClientException;

    void reconcileIdentities(
            Set<Identity> previous,
            Set<Identity> desired
    ) throws PostgreSqlClientException;

    void synchronize(Set<String> databases, Set<Grant> desired)
            throws PostgreSqlClientException;
}
