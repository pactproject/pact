package io.github.pactproject.elasticsearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.pactproject.api.Access;
import io.github.pactproject.api.Identity;
import io.github.pactproject.api.PactState;
import io.github.pactproject.api.Resource;
import io.github.pactproject.api.SecretValue;
import io.github.pactproject.api.exception.BackendOperationException;
import io.github.pactproject.api.exception.ValidationException;
import io.github.pactproject.api.value.Value;
import io.github.pactproject.elasticsearch.api.ElasticsearchClient;
import io.github.pactproject.elasticsearch.api.ElasticsearchClientException;
import io.github.pactproject.elasticsearch.compile.ElasticsearchRoleCompiler;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElasticsearchBackendTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void assignsOwnedRoleAndPreservesUnrelatedRolesThenRemovesOnlyItsRole()
            throws Exception {
        FakeClient client = new FakeClient();
        client.users.put("alice", user("alice", "other-role", "ops"));
        ElasticsearchBackend backend = backend(client, "require");
        PactState desired = state(access("alice", "logs-*", "read"));

        backend.prepare(PactState.empty(), desired).apply();
        String role = roleName("alice");
        assertTrue(client.roles.containsKey(role));
        assertTrue(client.roles.get(role).path("metadata")
                .path("pact_managed_by").asText().equals("pact"));
        assertEquals(Set.of("other-role", "ops", role), client.rolesOf("alice"));
        assertEquals("unchanged", client.users.get("alice")
                .path("metadata").path("note").asText());

        backend.prepare(desired, PactState.empty()).apply();
        assertFalse(client.roles.containsKey(role));
        assertEquals(Set.of("other-role", "ops"), client.rolesOf("alice"));
    }

    @Test
    void ensureCreatesUserWithUnusableRandomPasswordAndLeavesUserOnRemoval()
            throws Exception {
        FakeClient client = new FakeClient();
        ElasticsearchBackend backend = backend(client, "ensure");
        PactState initial = new PactState(
                Set.of(access("ensure-user", "logs-*", "read")),
                Set.of(new Identity(
                        "search", "ensure-user", true, null, null, null
                ))
        );

        backend.prepare(PactState.empty(), initial).apply();
        assertNotNull(client.users.get("ensure-user"));
        assertTrue(client.users.get("ensure-user").path("roles").isArray());
        assertFalse(client.users.get("ensure-user").has("password"));
        backend.prepare(initial, PactState.empty()).apply();
        assertFalse(client.roles.containsKey(roleName("ensure-user")));
        assertNotNull(client.users.get("ensure-user"));
    }

    @Test
    void createsUserWithSecretAndRotatesOnlyWhenSecretVersionChanges()
            throws Exception {
        FakeClient client = new FakeClient();
        ElasticsearchBackend backend = backend(client, "ensure");
        Identity oldIdentity = identity("1", "old-password");
        PactState oldState = state(
                oldIdentity,
                access("alice", "logs-*", "read")
        );
        client.users.put("alice", user("alice"));

        Identity rotatedIdentity = identity("2", "new-password");
        PactState rotatedState = state(
                rotatedIdentity,
                access("alice", "logs-*", "read")
        );
        backend.prepare(oldState, rotatedState).apply();
        assertEquals("new-password", client.passwordUpdates.get("alice"));

        client.passwordUpdates.clear();
        Identity sameVersion = identity("2", "not-applied");
        backend.prepare(
                rotatedState,
                state(
                        sameVersion,
                        access("alice", "logs-*", "read")
                )
        ).apply();
        assertFalse(client.passwordUpdates.containsKey("alice"));

        FakeClient creationClient = new FakeClient();
        ElasticsearchBackend creationBackend =
                backend(creationClient, "ensure");
        creationBackend.prepare(
                PactState.empty(),
                state(
                        new Identity(
                                "search",
                                "new-user",
                                true,
                                "default/credentials#password",
                                "1",
                                new SecretValue("created-password")
                        ),
                        access("new-user", "logs-*", "read")
                )
        ).apply();
        assertEquals(
                "created-password",
                creationClient.passwordsUsedOnCreate.get("new-user")
        );
    }

    @Test
    void requiresEnsureIdentityAndRefusesUnownedRoleCollision() {
        FakeClient client = new FakeClient();
        ElasticsearchBackend backend = backend(client, "ensure");
        PactState withoutEnsure = state(access("alice", "logs-*", "read"));
        assertThrows(ValidationException.class, () ->
                backend.prepare(PactState.empty(), withoutEnsure));

        String roleName = roleName("alice");
        ObjectNode unmanaged = MAPPER.createObjectNode();
        unmanaged.putObject("metadata").put("owner", "other");
        client.roles.put(roleName, unmanaged);
        PactState desired = new PactState(
                withoutEnsure.accesses(),
                Set.of(new Identity(
                        "search", "alice", true, null, null, null
                ))
        );
        assertThrows(ValidationException.class, () ->
                backend.prepare(PactState.empty(), desired));
        assertEquals("other", client.roles.get(roleName)
                .path("metadata").path("owner").asText());
    }

    @Test
    void refusesRoleThatBecomesUnmanagedAfterPreparation() throws Exception {
        FakeClient client = new FakeClient();
        client.users.put("alice", user("alice"));
        ElasticsearchBackend backend = backend(client, "require");
        PactState desired = state(access("alice", "logs-*", "read"));
        var transaction = backend.prepare(PactState.empty(), desired);

        ObjectNode unmanaged = MAPPER.createObjectNode();
        unmanaged.putObject("metadata").put("owner", "other");
        client.roles.put(roleName("alice"), unmanaged);

        assertThrows(BackendOperationException.class, transaction::apply);
        assertEquals("other", client.roles.get(roleName("alice"))
                .path("metadata").path("owner").asText());
    }

    @Test
    void rollsBackCreatedRoleAndUserAssignmentAfterApplyFailure()
            throws Exception {
        FakeClient client = new FakeClient();
        client.users.put("alice", user("alice", "external"));
        client.failUserUpdate = true;
        ElasticsearchBackend backend = backend(client, "require");
        PactState desired = state(access("alice", "logs-*", "read"));
        var transaction = backend.prepare(PactState.empty(), desired);
        assertThrows(BackendOperationException.class, transaction::apply);

        client.failUserUpdate = false;
        transaction.rollback();
        assertFalse(client.roles.containsKey(roleName("alice")));
        assertEquals(Set.of("external"), client.rolesOf("alice"));
    }

    private static ElasticsearchBackend backend(
            FakeClient client,
            String principalMode
    ) {
        return new ElasticsearchBackend(
                "search",
                new ElasticsearchConfig(
                        URI.create("http://localhost:9200"),
                        "pact",
                        "secret",
                        null,
                        principalMode
                ),
                client
        );
    }

    private static PactState state(Access... accesses) {
        return new PactState(Set.of(accesses));
    }

    private static PactState state(
            Identity identity,
            Access... accesses)
    {
        return new PactState(
                Set.of(accesses),
                Set.of(identity)
        );
    }

    private static Identity identity(String version, String password) {
        return new Identity(
                "search",
                "alice",
                true,
                "default/credentials#password",
                version,
                new SecretValue(password)
        );
    }

    private static Access access(
            String principal,
            String index,
            String privilege
    ) {
        return new Access(
                principal,
                new Resource("search", Map.of("index", index)),
                Map.of("permissions", Value.object(Map.of(
                        "indices", Value.set(Set.of(Value.string(privilege)))
                )))
        );
    }

    private static ObjectNode user(String name, String... roles) {
        ObjectNode user = MAPPER.createObjectNode();
        user.put("username", name);
        user.putArray("roles").addAll(java.util.Arrays.stream(roles)
                .map(MAPPER.getNodeFactory()::textNode)
                .toList());
        user.put("enabled", true);
        user.putObject("metadata").put("note", "unchanged");
        return user;
    }

    private static String roleName(String principal) {
        return ElasticsearchRoleCompiler.roleName("search", principal);
    }

    private static final class FakeClient implements ElasticsearchClient {
        private final Map<String, JsonNode> roles = new HashMap<>();
        private final Map<String, JsonNode> users = new HashMap<>();
        private final Map<String, String> passwordsUsedOnCreate =
                new HashMap<>();
        private final Map<String, String> passwordUpdates = new HashMap<>();
        private boolean failUserUpdate;

        @Override
        public JsonNode getRole(String name) {
            JsonNode role = roles.get(name);
            return role == null ? null : role.deepCopy();
        }

        @Override
        public JsonNode getUser(String username) {
            JsonNode user = users.get(username);
            return user == null ? null : user.deepCopy();
        }

        @Override
        public void putRole(String name, JsonNode definition) {
            roles.put(name, definition.deepCopy());
        }

        @Override
        public void deleteRole(String name) {
            roles.remove(name);
        }

        @Override
        public void putUser(String username, JsonNode definition)
                throws ElasticsearchClientException {
            if (failUserUpdate && !definition.has("password")) {
                throw new ElasticsearchClientException("deliberate failure");
            }
            ObjectNode stored = definition.deepCopy();
            stored.put("username", username);
            if (definition.hasNonNull("password")) {
                passwordsUsedOnCreate.put(
                        username,
                        definition.path("password").asText()
                );
            }
            stored.remove("password");
            users.put(username, stored);
        }

        @Override
        public void updatePassword(String username, String password) {
            passwordUpdates.put(username, password);
        }

        private Set<String> rolesOf(String username) {
            java.util.TreeSet<String> result = new java.util.TreeSet<>();
            users.get(username).path("roles")
                    .forEach(role -> result.add(role.asText()));
            return Set.copyOf(result);
        }
    }
}
