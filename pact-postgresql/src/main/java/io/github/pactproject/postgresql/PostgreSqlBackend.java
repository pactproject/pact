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
import io.github.pactproject.postgresql.model.Grant;

import java.util.HashSet;
import java.util.Set;

public final class PostgreSqlBackend implements Backend {
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
    public void apply(PactState state) throws BackendException {
        prepare(PactState.empty(), state).apply();
    }

    @Override
    public BackendTransaction prepare(
            PactState previousState,
            PactState desiredState
    ) throws BackendException {
        compile(previousState);
        Set<Grant> desiredGrants = compile(desiredState);
        Set<Identity> previousIdentities =
                compileIdentities(previousState);
        Set<Identity> desiredIdentities =
                compileIdentities(desiredState);
        Set<String> databases = new HashSet<>(
                GrantCompiler.databases(previousState, id)
        );
        databases.addAll(GrantCompiler.databases(desiredState, id));
        Set<String> scope = Set.copyOf(databases);
        Set<Grant> snapshot;
        try {
            snapshot = client.getManagedGrants(scope);
        }
        catch (PostgreSqlClientException e) {
            throw new BackendOperationException(
                    "Failed to prepare PostgreSQL grants for backend '"
                            + id + "'",
                    e
            );
        }
        return new BackendTransaction() {
            @Override
            public void apply() throws BackendException {
                reconcileIdentities(
                        previousIdentities,
                        desiredIdentities
                );
                synchronize(scope, desiredGrants, "apply");
            }

            @Override
            public void rollback() throws BackendException {
                synchronize(scope, snapshot, "rollback");
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

    private Set<Grant> compile(PactState state)
            throws ValidationException {
        try {
            return GrantCompiler.compile(state, id);
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
            String operation
    ) throws BackendException {
        try {
            client.synchronize(databases, desired);
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
