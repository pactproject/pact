package io.github.pactproject.postgresql;

import io.github.pactproject.api.Backend;
import io.github.pactproject.api.BackendTransaction;
import io.github.pactproject.api.Identity;
import io.github.pactproject.api.PactState;
import io.github.pactproject.api.exception.BackendException;
import io.github.pactproject.api.exception.BackendOperationException;
import io.github.pactproject.api.exception.ValidationException;
import io.github.pactproject.postgresql.api.PostgreSqlClient;
import io.github.pactproject.postgresql.api.PostgreSqlClientException;
import io.github.pactproject.postgresql.compile.GrantCompiler;
import io.github.pactproject.postgresql.compile.CompiledPostgreSqlState;
import io.github.pactproject.postgresql.model.DefaultPrivilegeGrant;
import io.github.pactproject.postgresql.model.DefaultPrivilegeOverride;
import io.github.pactproject.postgresql.model.DefaultPrivilegeScope;
import io.github.pactproject.postgresql.model.Grant;
import io.github.pactproject.postgresql.model.Ownership;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

public final class PostgreSqlBackend implements Backend {
    private static final Logger log =
            LoggerFactory.getLogger(PostgreSqlBackend.class);

    private final String id;
    private final PostgreSqlClient client;

    public PostgreSqlBackend(String id, PostgreSqlClient client) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException(
                    "PostgreSQL backend id must not be blank"
            );
        }
        this.id = id;
        this.client = client;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public void validateIdentities(Set<Identity> identities) throws ValidationException {
        for (Identity identity : identities) {
            if (!id.equals(identity.backendId())) {
                throw new ValidationException("Identity backend does not match PostgreSQL backend '" + id + "'");
            }
        }
    }

    @Override
    public void apply(PactState state) throws BackendException {
        prepareTransaction(PactState.empty(), state).apply();
    }

    @Override
    public BackendTransaction prepareTransaction(
            PactState previousState,
            PactState desiredState
    ) throws BackendException {
        return prepareTransaction(previousState, desiredState, Set.of());
    }

    @Override
    public BackendTransaction prepareTransaction(PactState previousState, PactState desiredState,
            Set<PactState> recoveryStates) throws BackendException {
        CompiledPostgreSqlState previous = compileState(previousState);
        CompiledPostgreSqlState desired = compileState(desiredState);
        Set<Grant> desiredGrants = desired.grants();
        Set<Identity> previousIdentities =
                compileIdentities(previousState);
        Set<Identity> desiredIdentities =
                compileIdentities(desiredState);
        Set<String> databases = new HashSet<>(
                GrantCompiler.databases(previousState, id)
        );
        databases.addAll(GrantCompiler.databases(desiredState, id));
        Set<DefaultPrivilegeScope> defaultScopes = new HashSet<>(
                previous.defaultPrivilegeScopes()
        );
        defaultScopes.addAll(desired.defaultPrivilegeScopes());
        for (PactState recoveryState : recoveryStates) {
            var recovery = compileState(recoveryState);
            compileIdentities(recoveryState);
            databases.addAll(GrantCompiler.databases(recoveryState, id));
            defaultScopes.addAll(recovery.defaultPrivilegeScopes());
        }
        Set<String> scope = Set.copyOf(databases);
        Set<DefaultPrivilegeScope> transitionDefaultScopes =
                Set.copyOf(defaultScopes);
        Set<Grant> observedGrants;
        try {
            client.validateOwnerships(desired.ownerships(), desiredIdentities.stream()
                    .filter(Identity::ensure).map(Identity::principal)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet()));
            observedGrants = client.getManagedGrants(scope);
            client.getManagedDefaultPrivileges(
                    transitionDefaultScopes
            );
        }
        catch (PostgreSqlClientException e) {
            throw new BackendOperationException(
                    "Failed to prepare PostgreSQL grants for backend '"
                            + id + "'",
                    e
            );
        }
        log.debug(
                "Prepared PostgreSQL backend '{}' with {} desired grant(s), "
                        + "{} actual grant(s), {} database(s), and {} desired identity/identities",
                id,
                desiredGrants.size(),
                observedGrants.size(),
                scope.size(),
                desiredIdentities.size()
        );
        Set<DefaultPrivilegeOverride> defaultOverrides =
                desired.defaultPrivilegeOverrides();
        log.debug("backend={} phase=prepare databases={} desired_grants={} desired_defaults={} desired_ownerships={} cleanup_default_scopes={}",
                id, scope, desiredGrants, desired.defaultPrivileges(), desired.ownerships(), transitionDefaultScopes);
        return new BackendTransaction() {
            @Override
            public void apply() throws BackendException {
                desiredIdentities.forEach(identity -> log.debug("backend={} action=reconcile_identity principal={} ensure={} password_update_requested={}",
                        id, identity.principal(), identity.ensure(), identity.password() != null));
                log.info(
                        "Reconciling PostgreSQL backend '{}' ({} identity/identities, "
                                + "{} grant(s) across {} database(s))",
                        id,
                        desiredIdentities.size(),
                        desiredGrants.size(),
                        scope.size()
                );
                reconcileIdentities(
                        previousIdentities,
                        desiredIdentities
                );
                synchronize(
                        scope,
                        desiredGrants,
                        desired.defaultPrivileges(),
                        transitionDefaultScopes,
                        defaultOverrides,
                        desired.ownerships(),
                        "apply"
                );
                log.info("Reconciled PostgreSQL backend '{}'", id);
            }

            @Override
            public void rollback() throws BackendException {
                log.info(
                        "Rolling back PostgreSQL grants for backend '{}' "
                                + "({} grant(s) across {} database(s))",
                        id,
                        previous.grants().size(),
                        scope.size()
                );
                synchronize(
                        scope,
                        previous.grants(),
                        previous.defaultPrivileges(),
                        transitionDefaultScopes,
                        previous.defaultPrivilegeOverrides(),
                        previous.ownerships(),
                        "rollback"
                );
                log.info("Rolled back PostgreSQL grants for backend '{}'", id);
            }
        };
    }

    private Set<Identity> compileIdentities(PactState state)
            throws ValidationException {
        for (Identity identity : state.identities()) {
            if (!id.equals(identity.backendId())) {
                throw new ValidationException(
                        "Identity backend '" + identity.backendId()
                                + "' does not match PostgreSQL backend '"
                                + id + "'"
                );
            }
        }
        return state.identities();
    }

    private void reconcileIdentities(
            Set<Identity> previous,
            Set<Identity> desired
    ) throws BackendException {
        try {
            client.reconcileIdentities(previous, desired);
        }
        catch (PostgreSqlClientException e) {
            throw new BackendOperationException(
                    "Failed to reconcile PostgreSQL identities for backend '"
                            + id + "'",
                    e
            );
        }
    }

    private CompiledPostgreSqlState compileState(PactState state)
            throws ValidationException {
        try {
            return GrantCompiler.compileState(state, id);
        }
        catch (IllegalArgumentException e) {
            throw new ValidationException(
                    "Invalid PostgreSQL access configuration for backend '"
                            + id + "'",
                    e
            );
        }
    }

    private void synchronize(
            Set<String> databases,
            Set<Grant> desired,
            Set<DefaultPrivilegeGrant> defaults,
            Set<DefaultPrivilegeScope> defaultScopes,
            Set<DefaultPrivilegeOverride> overrides,
            Set<Ownership> ownerships,
            String operation
    ) throws BackendException {
        log.debug("backend={} phase={} databases={} target_grants={} target_defaults={} target_ownerships={}",
                id, operation, databases, desired, defaults, ownerships);
        try {
            client.synchronize(
                    databases, desired, defaults, defaultScopes, overrides, ownerships
            );
        }
        catch (PostgreSqlClientException e) {
            throw new BackendOperationException(
                    "Failed to " + operation
                            + " PostgreSQL grants for backend '"
                            + id + "'",
                    e
            );
        }
    }
}
