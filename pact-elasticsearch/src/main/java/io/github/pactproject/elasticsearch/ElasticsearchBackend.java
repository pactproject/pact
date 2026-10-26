package io.github.pactproject.elasticsearch;

import io.github.pactproject.api.Backend;
import io.github.pactproject.api.BackendTransaction;
import io.github.pactproject.api.Identity;
import io.github.pactproject.api.PactState;
import io.github.pactproject.api.exception.BackendException;
import io.github.pactproject.api.exception.BackendOperationException;
import io.github.pactproject.api.exception.ValidationException;
import io.github.pactproject.elasticsearch.api.ElasticsearchClient;
import io.github.pactproject.elasticsearch.api.ElasticsearchClientException;
import io.github.pactproject.elasticsearch.compile.ElasticsearchRoleCompiler;
import io.github.pactproject.elasticsearch.sync.ElasticsearchSync;
import java.util.Map;
import java.util.TreeMap;

public final class ElasticsearchBackend implements Backend {
    private final String id;
    private final ElasticsearchConfig config;
    private final ElasticsearchClient client;

    public ElasticsearchBackend(String id, ElasticsearchConfig config, ElasticsearchClient client) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Elasticsearch backend id must not be blank");
        this.id = id;
        this.config = java.util.Objects.requireNonNull(config);
        this.client = java.util.Objects.requireNonNull(client);
    }

    @Override public String id() { return id; }

    @Override public void validateIdentities(java.util.Set<Identity> identities) throws ValidationException {
        for (var identity : identities) {
            if (!id.equals(identity.backendId())) throw new ValidationException("Identity backend does not match Elasticsearch backend '" + id + "'");
        }
    }

    @Override public void apply(PactState state) throws BackendException { prepareTransaction(PactState.empty(), state).apply(); }

    @Override public BackendTransaction prepareTransaction(PactState previous, PactState desired) throws BackendException {
        return prepareTransaction(previous, desired, java.util.Set.of());
    }

    @Override public BackendTransaction prepareTransaction(PactState previous, PactState desired,
            java.util.Set<PactState> recoveryStates) throws BackendException {
        try {
            var recoveryRoles = new TreeMap<String, java.util.Set<String>>();
            for (var recovery : recoveryStates) {
                ElasticsearchRoleCompiler.compile(recovery, id).forEach((principal, role) ->
                        recoveryRoles.computeIfAbsent(principal, ignored -> new java.util.TreeSet<>()).addAll(role.assignedRoles()));
                identities(recovery, false).keySet().forEach(principal ->
                        recoveryRoles.computeIfAbsent(principal, ignored -> new java.util.TreeSet<>()));
            }
            return ElasticsearchSync.prepareTransaction(id, config, client,
                    ElasticsearchRoleCompiler.compile(previous, id), ElasticsearchRoleCompiler.compile(desired, id),
                    identities(previous, false), identities(desired, true), recoveryRoles);
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Invalid Elasticsearch configuration for backend '" + id + "': " + e.getMessage(), e);
        } catch (ElasticsearchClientException e) {
            throw new BackendOperationException("Failed to prepare Elasticsearch backend '" + id + "'", e);
        }
    }

    private Map<String, Identity> identities(PactState state, boolean desired) {
        var result = new TreeMap<String, Identity>();
        for (var identity : state.identities()) {
            if (!identity.backendId().equals(id)) throw new IllegalArgumentException("Identity backend does not match Elasticsearch backend '" + id + "'");
            if (desired && identity.passwordSource() != null && identity.password() == null) {
                throw new IllegalArgumentException("Password Secret was not resolved for Elasticsearch user '" + identity.principal() + "'");
            }
            if (result.put(identity.principal(), identity) != null) throw new IllegalArgumentException("Duplicate Elasticsearch identity: " + identity.principal());
        }
        return result;
    }

    @Override public void close() throws BackendOperationException {
        try { client.close(); }
        catch (ElasticsearchClientException e) { throw new BackendOperationException("Failed to close Elasticsearch client", e); }
    }
}
