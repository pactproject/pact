package io.github.pactproject.elasticsearch.sync;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.pactproject.api.BackendTransaction;
import io.github.pactproject.api.Identity;
import io.github.pactproject.api.exception.BackendException;
import io.github.pactproject.api.exception.BackendOperationException;
import io.github.pactproject.api.exception.ValidationException;
import io.github.pactproject.elasticsearch.ElasticsearchConfig;
import io.github.pactproject.elasticsearch.ElasticsearchReconciliationMode;
import io.github.pactproject.elasticsearch.api.ElasticsearchClient;
import io.github.pactproject.elasticsearch.api.ElasticsearchClientException;
import io.github.pactproject.elasticsearch.compile.ElasticsearchRoleCompiler;
import io.github.pactproject.elasticsearch.model.ElasticsearchRole;
import java.security.SecureRandom;
import java.util.*;

/** Read-only preparation and compensating synchronization within declared native-user scope. */
public final class ElasticsearchSync {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ElasticsearchSync.class);
    private ElasticsearchSync() { }
    private static final SecureRandom RANDOM = new SecureRandom();

    public static BackendTransaction prepareTransaction(String backendId, ElasticsearchConfig config, ElasticsearchClient client,
            Map<String, ElasticsearchRole> previous, Map<String, ElasticsearchRole> desired,
            Map<String, Identity> previousIdentities, Map<String, Identity> desiredIdentities)
            throws ElasticsearchClientException, ValidationException {
        return prepareTransaction(backendId, config, client, previous, desired, previousIdentities, desiredIdentities, Map.of());
    }

    public static BackendTransaction prepareTransaction(String backendId, ElasticsearchConfig config, ElasticsearchClient client,
            Map<String, ElasticsearchRole> previous, Map<String, ElasticsearchRole> desired,
            Map<String, Identity> previousIdentities, Map<String, Identity> desiredIdentities,
            Map<String, Set<String>> recoveryRoles) throws ElasticsearchClientException, ValidationException {
        var scope = new TreeSet<String>(previous.keySet());
        scope.addAll(desired.keySet());
        scope.addAll(previousIdentities.keySet());
        scope.addAll(desiredIdentities.keySet());
        scope.addAll(recoveryRoles.keySet());
        validateReferences(client, desired);
        for (String principal : scope) {
            var role = client.getRole(ElasticsearchRoleCompiler.roleName(backendId, principal));
            if (role != null && !ElasticsearchDocuments.owned(role, backendId, principal)) {
                throw new ValidationException("Elasticsearch generated role name collision for user '" + principal + "'");
            }
            var user = client.getUser(principal);
            validateUser(principal, user, config, desired.containsKey(principal) || desiredIdentities.containsKey(principal), desiredIdentities.get(principal));
        }
        var cleanupRoles = new TreeMap<String, Set<String>>();
        recoveryRoles.forEach((principal, roles) -> cleanupRoles.put(principal, Set.copyOf(roles)));
        return new Transaction(backendId, config, client, previous, desired, previousIdentities, desiredIdentities, scope,
                Map.copyOf(cleanupRoles));
    }

    private static void validateReferences(ElasticsearchClient client, Map<String, ElasticsearchRole> desired)
            throws ElasticsearchClientException, ValidationException {
        if (desired.values().stream().anyMatch(ElasticsearchRole::hasPrivileges)) {
            var supported = client.getPrivileges();
            for (var role : desired.values()) {
                if (!supported.cluster().containsAll(role.cluster()) || role.indices().values().stream()
                        .anyMatch(values -> !supported.indices().containsAll(values))) {
                    throw new ValidationException("Unknown Elasticsearch privilege in declaration for '" + role.principal() + "'");
                }
            }
        }
        var referenced = new TreeSet<String>();
        desired.values().forEach(role -> referenced.addAll(role.assignedRoles()));
        for (String name : referenced) {
            var role = client.getRole(name);
            if (role == null) throw new ValidationException("Elasticsearch role does not exist: " + name);
            if (role.path("metadata").path("pact_managed_by").asText().equals("pact")) {
                throw new ValidationException("Generated Pact roles cannot be referenced through roles: " + name + "; declare permissions instead");
            }
        }
    }

    private static void validateUser(String principal, JsonNode user, ElasticsearchConfig config, boolean desired, Identity identity)
            throws ValidationException {
        if (user != null && user.path("metadata").path("_reserved").asBoolean()) {
            throw new ValidationException("Elasticsearch built-in user cannot be managed by PACT: " + principal);
        }
        if (desired && user == null && (!config.principalMode().equals("ensure") || identity == null || !identity.ensure())) {
            throw new ValidationException("Elasticsearch native user does not exist: " + principal + "; explicit ensure identity and principal-mode=ensure are required");
        }
    }

    private static final class Transaction implements BackendTransaction {
        final String backendId;
        final ElasticsearchConfig config;
        final ElasticsearchClient client;
        final Map<String, ElasticsearchRole> previous, desired;
        final Map<String, Identity> previousIdentities, desiredIdentities;
        final Set<String> scope;
        final Map<String, Set<String>> recoveryRoles;
        boolean started;

        Transaction(String backendId, ElasticsearchConfig config, ElasticsearchClient client,
                Map<String, ElasticsearchRole> previous, Map<String, ElasticsearchRole> desired,
                Map<String, Identity> previousIdentities, Map<String, Identity> desiredIdentities,
                Set<String> scope, Map<String, Set<String>> recoveryRoles) {
            this.backendId = backendId; this.config = config; this.client = client;
            this.previous = previous; this.desired = desired;
            this.previousIdentities = previousIdentities; this.desiredIdentities = desiredIdentities;
            this.scope = scope;
            this.recoveryRoles = recoveryRoles;
        }

        @Override public void apply() throws BackendException {
            started = true;
            long began = System.nanoTime();
            var completed = new TreeMap<String, Integer>();
            log.info("backend={} phase=sync result=started scope_users={} desired_users={} mode={}",
                    backendId, scope.size(), desired.size(), config.reconciliationMode());
            try {
                // Recheck all references and ownership before the first write, including prepare/apply races.
                validateReferences(client, desired);
                var users = new HashMap<String, JsonNode>();
                var roles = new HashMap<String, JsonNode>();
                for (String principal : scope) {
                    var role = client.getRole(roleName(principal));
                    requireOwned(role, principal);
                    roles.put(principal, role);
                    var user = client.getUser(principal);
                    validateUser(principal, user, config, desired.containsKey(principal) || desiredIdentities.containsKey(principal), desiredIdentities.get(principal));
                    users.put(principal, user);
                }
                var created = new HashSet<String>();
                for (String principal : scope) {
                    if (users.get(principal) == null && (desired.containsKey(principal) || desiredIdentities.containsKey(principal))) {
                        var body = ElasticsearchDocuments.JSON.createObjectNode();
                        var identity = desiredIdentities.get(principal);
                        body.put("password", identity.password() == null ? generatedPassword() : identity.password().reveal());
                        body.putArray("roles");
                        body.put("enabled", true);
                        log.debug("backend={} action=ensure_user result=started principal={}", backendId, principal);
                        client.putUser(principal, body);
                        completed.merge("ensure_user", 1, Integer::sum);
                        log.debug("backend={} action=ensure_user result=completed principal={}", backendId, principal);
                        // Never retain the generated password or creation request in transaction state.
                        users.put(principal, client.getUser(principal));
                        if (users.get(principal) == null) throw new ElasticsearchClientException("Ensured Elasticsearch user is missing");
                        created.add(principal);
                    }
                }
                for (String principal : scope) {
                    var role = desired.get(principal);
                    if (role != null && role.hasPrivileges()) {
                        var definition = ElasticsearchDocuments.role(backendId, role);
                        if (!ElasticsearchDocuments.sameRole(roles.get(principal), definition)) {
                            if (log.isDebugEnabled()) log.debug("backend={} action=put_role result=started principal={} role={} definition={}",
                                    backendId, principal, role.name(), io.github.pactproject.api.DiagnosticSanitizer.redact(definition.toString()));
                            client.putRole(role.name(), definition);
                            completed.merge("put_role", 1, Integer::sum);
                            log.debug("backend={} action=put_role result=completed role={}", backendId, role.name());
                        }
                    }
                }
                for (String principal : scope) {
                    var user = users.get(principal);
                    if (user == null) continue;
                    var wanted = desiredRoles(principal);
                    if (config.reconciliationMode() == ElasticsearchReconciliationMode.MANAGED) {
                        var merged = ElasticsearchDocuments.roles(user);
                        merged.removeAll(managedRoles(principal));
                        merged.addAll(wanted);
                        wanted = merged;
                    }
                    if (updateUser(principal, user, wanted)) completed.merge("assign_roles", 1, Integer::sum);
                }
                for (String principal : scope) {
                    var role = desired.get(principal);
                    if ((role == null || !role.hasPrivileges()) && roles.get(principal) != null) {
                        log.debug("backend={} action=delete_role result=started principal={} role={}", backendId, principal, roleName(principal));
                        client.deleteRole(roleName(principal));
                        completed.merge("delete_role", 1, Integer::sum);
                        log.debug("backend={} action=delete_role result=completed role={}", backendId, roleName(principal));
                    }
                }
                for (var entry : desiredIdentities.entrySet()) {
                    var identity = entry.getValue();
                    var previousIdentity = previousIdentities.get(entry.getKey());
                    if (!created.contains(entry.getKey()) && identity.password() != null &&
                            (previousIdentity == null || !Objects.equals(previousIdentity.passwordSource(), identity.passwordSource())
                                    || !Objects.equals(previousIdentity.passwordVersion(), identity.passwordVersion()))) {
                        log.debug("backend={} action=update_password result=started principal={}", backendId, entry.getKey());
                        client.updatePassword(entry.getKey(), identity.password().reveal());
                        completed.merge("update_password", 1, Integer::sum);
                        log.debug("backend={} action=update_password result=completed principal={}", backendId, entry.getKey());
                    }
                }
                log.info("backend={} phase=sync result=completed duration_ms={} completed_writes={}", backendId,
                        (System.nanoTime() - began) / 1_000_000, completed);
            } catch (ElasticsearchClientException | ValidationException e) {
                log.warn("backend={} phase=sync result=failed duration_ms={} confirmed_writes={} error_type={}", backendId,
                        (System.nanoTime() - began) / 1_000_000, completed, e.getClass().getSimpleName());
                throw new BackendOperationException("Failed to synchronize Elasticsearch backend '" + backendId + "'", e);
            }
        }

        private Set<String> desiredRoles(String principal) {
            var result = new TreeSet<String>();
            var role = desired.get(principal);
            if (role != null) {
                result.addAll(role.assignedRoles());
                if (role.hasPrivileges()) result.add(role.name());
            }
            return result;
        }

        private Set<String> managedRoles(String principal) {
            var result = new TreeSet<String>();
            result.add(roleName(principal));
            result.addAll(recoveryRoles.getOrDefault(principal, Set.of()));
            if (previous.containsKey(principal)) result.addAll(previous.get(principal).assignedRoles());
            if (desired.containsKey(principal)) result.addAll(desired.get(principal).assignedRoles());
            return result;
        }

        private String roleName(String principal) { return ElasticsearchRoleCompiler.roleName(backendId, principal); }

        private void requireOwned(JsonNode role, String principal) throws ElasticsearchClientException {
            if (role != null && !ElasticsearchDocuments.owned(role, backendId, principal)) {
                throw new ElasticsearchClientException("Refusing to overwrite or delete an Elasticsearch role not owned by PACT: " + roleName(principal));
            }
        }

        private boolean updateUser(String principal, JsonNode current, Set<String> roles) throws ElasticsearchClientException {
            if (!ElasticsearchDocuments.roles(current).equals(roles)) {
                log.debug("backend={} action=assign_roles result=started principal={} roles={}", backendId, principal, roles);
                client.putUser(principal, ElasticsearchDocuments.userUpdate(current, roles));
                log.debug("backend={} action=assign_roles result=completed principal={} roles={}", backendId, principal, roles);
                return true;
            }
            return false;
        }

        @Override public void rollback() throws BackendException {
            if (!started) return;
            var restoredIdentities = new TreeMap<String, Identity>();
            previousIdentities.forEach((principal, identity) ->
                    restoredIdentities.put(principal, identity.withoutPassword()));
            try {
                // Reverse the declarative transition, retaining both user scopes.
                // Credentials are irreversible; do not rotate them during restoration.
                ElasticsearchSync.prepareTransaction(backendId, config, client,
                        desired, previous, desiredIdentities, restoredIdentities, recoveryRoles).apply();
            } catch (ElasticsearchClientException | ValidationException e) {
                throw new BackendOperationException("Failed to restore previous Elasticsearch state for backend '" + backendId + "'", e);
            }
        }
    }

    private static String generatedPassword() {
        byte[] bytes = new byte[36];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
