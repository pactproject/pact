package io.github.pactproject.elasticsearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import io.github.pactproject.elasticsearch.model.ElasticsearchRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

public final class ElasticsearchBackend implements Backend {
    private static final Logger log =
            LoggerFactory.getLogger(ElasticsearchBackend.class);

    private static final String MANAGED_BY = "pact";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String id;
    private final ElasticsearchConfig config;
    private final ElasticsearchClient client;

    public ElasticsearchBackend(
            String id,
            ElasticsearchConfig config,
            ElasticsearchClient client
    ) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException(
                    "Elasticsearch backend id must not be blank"
            );
        }
        this.id = id;
        this.config = config;
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
        Map<String, ElasticsearchRole> previous = compile(previousState);
        Map<String, ElasticsearchRole> desired = compile(desiredState);
        Map<String, Identity> previousIdentities =
                identitiesByPrincipal(previousState);
        Map<String, Identity> desiredIdentities =
                identitiesByPrincipal(desiredState);
        Set<String> principals = new TreeSet<>(previous.keySet());
        principals.addAll(desired.keySet());
        Map<String, JsonNode> roleSnapshot = new HashMap<>();
        Map<String, JsonNode> userSnapshot = new HashMap<>();
        try {
            for (String principal : principals) {
                String roleName = roleName(principal);
                JsonNode role = client.getRole(roleName);
                if (role != null && isUnmanaged(role, principal)) {
                    throw new ValidationException(
                            "Elasticsearch role name collision: "
                                    + roleName + " is not owned by PACT"
                    );
                }
                roleSnapshot.put(principal, role);
                userSnapshot.put(principal, client.getUser(principal));
                if (desired.containsKey(principal)
                        && userSnapshot.get(principal) == null
                        && (!config.principalMode().equals("ensure")
                        || desiredIdentities.get(principal) == null
                        || !desiredIdentities.get(principal).ensure())) {
                    throw new ValidationException(
                            "Elasticsearch native user does not exist: "
                                    + principal
                    );
                }
            }
        }
        catch (ElasticsearchClientException e) {
            throw new BackendOperationException(
                    "Failed to prepare Elasticsearch access for backend '"
                            + id + "'",
                    e
            );
        }

        log.debug(
                "Prepared Elasticsearch backend '{}' with {} desired role(s) "
                        + "across {} principal(s)",
                id,
                desired.size(),
                principals.size()
        );
        return new BackendTransaction() {
            @Override
            public void apply() throws BackendException {
                log.info(
                        "Reconciling Elasticsearch backend '{}' "
                                + "({} role(s) across {} principal(s))",
                        id,
                        desired.size(),
                        principals.size()
                );
                try {
                    Set<String> createdDuringApply = new HashSet<>();
                    for (ElasticsearchRole role : desired.values()) {
                        JsonNode currentRole = client.getRole(role.name());
                        if (currentRole != null) {
                            requireOwned(currentRole, role.principal());
                        }
                        client.putRole(role.name(), roleDefinition(role));
                    }
                    for (String principal : desired.keySet()) {
                        if (client.getUser(principal) == null) {
                            client.putUser(
                                    principal,
                                    newUser(
                                            desired.get(principal).name(),
                                            passwordForCreation(
                                                    desiredIdentities.get(principal)
                                            )
                                    )
                            );
                            createdDuringApply.add(principal);
                        }
                    }
                    for (String principal : principals) {
                        if (createdDuringApply.contains(principal)) {
                            continue;
                        }
                        String role = roleName(principal);
                        JsonNode current = client.getUser(principal);
                        if (current == null) {
                            if (desired.containsKey(principal)) {
                                throw new ElasticsearchClientException(
                                        "Elasticsearch native user disappeared "
                                                + "during reconciliation: "
                                                + principal
                                );
                            }
                            continue;
                        }
                        boolean shouldHaveRole = desired.containsKey(principal);
                        updateRoleAssignment(current, principal, role,
                                shouldHaveRole);
                    }
                    for (String principal : previous.keySet()) {
                        if (!desired.containsKey(principal)) {
                            String role = roleName(principal);
                            JsonNode currentRole = client.getRole(role);
                            if (currentRole != null) {
                                requireOwned(currentRole, principal);
                                client.deleteRole(role);
                            }
                        }
                        log.info(
                                "Reconciled Elasticsearch backend '{}' "
                                        + "({} role(s) across {} principal(s))",
                                id,
                                desired.size(),
                                principals.size()
                        );
                    }
                    for (String principal : desired.keySet()) {
                        Identity identity = desiredIdentities.get(principal);
                        if (identity != null
                                && identity.password() != null
                                && userSnapshot.get(principal) != null
                                && passwordChanged(
                                        previousIdentities.get(principal),
                                        identity
                                )) {
                            client.updatePassword(
                                    principal,
                                    identity.password().reveal()
                            );
                        }
                    }
                }
                catch (ElasticsearchClientException e) {
                    throw new BackendOperationException(
                            "Failed to synchronize Elasticsearch access "
                                    + "for backend '" + id + "'",
                            e
                    );
                }
            }

            @Override
            public void rollback() throws BackendException {
                log.info(
                        "Rolling back Elasticsearch backend '{}' across {} principal(s)",
                        id,
                        principals.size()
                );
                ElasticsearchClientException failure = null;
                for (String principal : principals) {
                    String role = roleName(principal);
                    JsonNode original = roleSnapshot.get(principal);
                    try {
                        JsonNode current = client.getRole(role);
                        if (original == null) {
                            if (current != null) {
                                requireOwned(current, principal);
                                client.deleteRole(role);
                            }
                        }
                        else {
                            requireOwned(original, principal);
                            if (current != null) {
                                requireOwned(current, principal);
                            }
                            client.putRole(role, original);
                        }
                    }
                    catch (ElasticsearchClientException e) {
                        if (failure == null) {
                            failure = e;
                        }
                        else {
                            failure.addSuppressed(e);
                        }
                    }
                }
                for (String principal : principals) {
                    try {
                        JsonNode current = client.getUser(principal);
                        if (current == null) {
                            continue;
                        }
                        JsonNode original = userSnapshot.get(principal);
                        boolean originallyAssigned = original != null
                                && hasRole(original, roleName(principal));
                        updateRoleAssignment(
                                current,
                                principal,
                                roleName(principal),
                                originallyAssigned
                        );
                    }
                    catch (ElasticsearchClientException e) {
                        if (failure == null) {
                            failure = e;
                        }
                        else {
                            failure.addSuppressed(e);
                        }
                    }
                }
                if (failure != null) {
                    throw new BackendOperationException(
                            "Failed to roll back Elasticsearch access for "
                                    + "backend '" + id + "'",
                            failure
                    );
                }
                log.info("Rolled back Elasticsearch backend '{}'", id);
            }
        };
    }

    private Map<String, ElasticsearchRole> compile(PactState state)
            throws ValidationException {
        try {
            return ElasticsearchRoleCompiler.compile(state, id);
        }
        catch (IllegalArgumentException e) {
            throw new ValidationException(
                    "Invalid Elasticsearch access configuration for backend '"
                            + id + "'",
                    e
            );
        }
    }

    private Map<String, Identity> identitiesByPrincipal(PactState state)
            throws ValidationException {
        Map<String, Identity> result = new HashMap<>();
        for (Identity identity : state.identities()) {
            if (!identity.backendId().equals(id)) {
                throw new ValidationException(
                        "Identity backend '" + identity.backendId()
                                + "' does not match Elasticsearch backend '"
                                + id + "'"
                );
            }
            result.put(identity.principal(), identity);
        }
        return result;
    }

    private static String passwordForCreation(Identity identity) {
        return identity != null && identity.password() != null
                ? identity.password().reveal()
                : generatedPassword();
    }

    private static boolean passwordChanged(
            Identity previous,
            Identity desired)
    {
        return previous == null
                || !Objects.equals(
                        previous.passwordSource(),
                        desired.passwordSource()
                )
                || !Objects.equals(
                        previous.passwordVersion(),
                        desired.passwordVersion()
                );
    }

    private ObjectNode roleDefinition(ElasticsearchRole role) {
        ObjectNode definition = MAPPER.createObjectNode();
        definition.putArray("cluster");
        ArrayNode indices = definition.putArray("indices");
        role.indices().forEach((index, privileges) -> {
            ObjectNode entry = indices.addObject();
            entry.putArray("names").add(index);
            ArrayNode values = entry.putArray("privileges");
            privileges.forEach(values::add);
        });
        definition.putArray("applications");
        definition.putArray("run_as");
        ObjectNode metadata = definition.putObject("metadata");
        metadata.put("pact_managed_by", MANAGED_BY);
        metadata.put("pact_backend_id", id);
        metadata.put("pact_principal", role.principal());
        return definition;
    }

    private ObjectNode newUser(String role, String password) {
        ObjectNode user = MAPPER.createObjectNode();
        user.put("password", password);
        user.putArray("roles").add(role);
        user.put("enabled", true);
        return user;
    }

    private void updateRoleAssignment(
            JsonNode user,
            String username,
            String role,
            boolean shouldHaveRole
    ) throws ElasticsearchClientException {
        Set<String> roles = new TreeSet<>();
        JsonNode existingRoles = user.get("roles");
        if (existingRoles != null && existingRoles.isArray()) {
            existingRoles.forEach(value -> {
                if (value.isTextual()) {
                    roles.add(value.textValue());
                }
            });
        }
        if (shouldHaveRole) {
            roles.add(role);
        }
        else {
            roles.remove(role);
        }
        ObjectNode update = MAPPER.createObjectNode();
        ArrayNode roleValues = update.putArray("roles");
        roles.forEach(roleValues::add);
        copyIfPresent(user, update, "full_name");
        copyIfPresent(user, update, "email");
        copyIfPresent(user, update, "metadata");
        copyIfPresent(user, update, "enabled");
        client.putUser(username, update);
    }

    private static void copyIfPresent(
            JsonNode source,
            ObjectNode target,
            String field
    ) {
        if (source.has(field) && !source.get(field).isNull()) {
            target.set(field, source.get(field).deepCopy());
        }
    }

    private static boolean hasRole(JsonNode user, String role) {
        JsonNode roles = user.get("roles");
        if (roles == null || !roles.isArray()) {
            return false;
        }
        for (JsonNode item : roles) {
            if (role.equals(item.asText())) {
                return true;
            }
        }
        return false;
    }

    private boolean isUnmanaged(JsonNode role, String principal) {
        JsonNode metadata = role.get("metadata");
        return metadata == null
                || !MANAGED_BY.equals(
                        metadata.path("pact_managed_by").asText())
                || !id.equals(metadata.path("pact_backend_id").asText())
                || !principal.equals(
                        metadata.path("pact_principal").asText());
    }

    private void requireOwned(JsonNode role, String principal)
            throws ElasticsearchClientException {
        if (isUnmanaged(role, principal)) {
            throw new ElasticsearchClientException(
                    "Refusing to overwrite or delete an Elasticsearch role "
                            + "not owned by PACT: " + roleName(principal)
            );
        }
    }

    private String roleName(String principal) {
        return ElasticsearchRoleCompiler.roleName(id, principal);
    }

    private static String generatedPassword() {
        byte[] random = new byte[36];
        RANDOM.nextBytes(random);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }
}
