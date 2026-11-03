package io.github.pactproject.grafana;

import io.github.pactproject.api.*;
import io.github.pactproject.api.exception.*;
import io.github.pactproject.grafana.api.*;
import io.github.pactproject.grafana.compile.GrafanaCompiler;
import io.github.pactproject.grafana.model.*;
import io.github.pactproject.grafana.sync.*;
import java.util.*;

public final class GrafanaBackend implements Backend {
    private final String id;
    private final GrafanaClient client;

    public GrafanaBackend(String id, GrafanaClient client) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Grafana backend id must not be blank");
        this.id = id;
        this.client = Objects.requireNonNull(client);
    }

    @Override public String id() { return id; }
    @Override public void apply(PactState state) throws BackendException { prepareTransaction(PactState.empty(), state).apply(); }
    @Override public BackendTransaction prepareTransaction(PactState previous, PactState desired) throws BackendException {
        return prepareTransaction(previous, desired, Set.of());
    }

    @Override public BackendTransaction prepareTransaction(PactState previous, PactState desired, Set<PactState> recovery) throws BackendException {
        validateIdentities(previous.identities());
        validateIdentities(desired.identities());
        var oldDeclarations = GrafanaCompiler.compile(previous, id);
        var declarations = GrafanaCompiler.compile(desired, id);
        var recoveryDeclarations = new ArrayList<GrafanaDeclaration>();
        for (var state : recovery) {
            validateIdentities(state.identities());
            recoveryDeclarations.addAll(GrafanaCompiler.compile(state, id));
        }
        try {
            var resolver = new GrafanaResolver(client);
            var oldGrants = resolver.resolve(oldDeclarations, true);
            var wanted = resolver.resolve(declarations, false);
            var cleanup = new TreeSet<>(oldGrants.grants().keySet());
            cleanup.addAll(wanted.grants().keySet());
            cleanup.addAll(resolver.scope(recoveryDeclarations));
            var scope = Set.copyOf(cleanup);
            GrafanaSynchronizer.synchronize(id, client, resolver, scope, wanted, false);
            return new BackendTransaction() {
                @Override public void apply() throws BackendException { run(declarations, wanted, scope, false); }
                @Override public void rollback() throws BackendException { run(oldDeclarations, oldGrants, scope, true); }
            };
        } catch (GrafanaClientException e) { throw operation("prepare", e); }
        catch (RuntimeException e) { throw unchecked("prepare", e); }
    }

    private void run(List<GrafanaDeclaration> declarations, GrafanaResolver.Resolved expected, Set<GrafanaResource> scope, boolean rollback)
            throws BackendException {
        String phase = rollback ? "rollback" : "apply";
        try {
            var resolver = new GrafanaResolver(client);
            var wanted = resolver.resolve(declarations, rollback);
            if (!wanted.equals(expected)) throw new ValidationException("Grafana references changed after preparation; retry reconciliation");
            GrafanaSynchronizer.synchronize(id, client, resolver, scope, wanted, true);
        } catch (GrafanaClientException e) { throw operation(phase, e); }
        catch (RuntimeException e) { throw unchecked(phase, e); }
    }

    private BackendOperationException operation(String phase, GrafanaClientException e) {
        return new BackendOperationException("Grafana backend '" + id + "' failed to " + phase + ": " + e.getMessage(), e);
    }

    private BackendOperationException unchecked(String phase, RuntimeException e) {
        // Unexpected adapter errors must participate in core compensation without exposing raw credentials.
        return new BackendOperationException("Grafana backend '" + id + "' failed to " + phase + " (" + e.getClass().getSimpleName() + ")");
    }

    @Override public void close() throws BackendException {
        try { client.close(); }
        catch (GrafanaClientException e) { throw operation("close", e); }
        catch (RuntimeException e) { throw unchecked("close", e); }
    }
}
