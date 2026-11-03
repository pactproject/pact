package io.github.pactproject.postgresql.api;

import io.github.pactproject.postgresql.model.Grant;
import io.github.pactproject.postgresql.model.Ownership;
import io.github.pactproject.postgresql.model.DefaultPrivilegeGrant;
import io.github.pactproject.postgresql.model.DefaultPrivilegeOverride;
import io.github.pactproject.postgresql.model.DefaultPrivilegeScope;
import io.github.pactproject.api.Identity;

import java.util.Set;

public interface PostgreSqlClient {
    /** Read-only ownership validation; ensured roles may be created during apply. */
    default void validateOwnerships(Set<Ownership> desired, Set<String> ensuredRoles)
            throws PostgreSqlClientException {
        if (!desired.isEmpty()) {
            throw new PostgreSqlClientException("PostgreSQL client does not support ownership");
        }
    }

    default void synchronize(Set<String> databases, Set<Grant> desired,
            Set<DefaultPrivilegeGrant> desiredDefaults, Set<DefaultPrivilegeScope> defaultScopes,
            Set<DefaultPrivilegeOverride> overrides, Set<Ownership> ownerships)
            throws PostgreSqlClientException {
        if (!ownerships.isEmpty()) {
            throw new PostgreSqlClientException("PostgreSQL client does not support ownership");
        }
        synchronize(databases, desired, desiredDefaults, defaultScopes, overrides);
    }

    Set<Grant> getManagedGrants(Set<String> databases)
            throws PostgreSqlClientException;

    Set<DefaultPrivilegeGrant> getManagedDefaultPrivileges(
            Set<DefaultPrivilegeScope> scopes
    ) throws PostgreSqlClientException;

    void reconcileIdentities(
            Set<Identity> previous,
            Set<Identity> desired
    ) throws PostgreSqlClientException;

    void synchronize(
            Set<String> databases,
            Set<Grant> desired,
            Set<DefaultPrivilegeGrant> desiredDefaults,
            Set<DefaultPrivilegeScope> defaultScopes,
            Set<DefaultPrivilegeOverride> overrides
    )
            throws PostgreSqlClientException;
}
