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
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(ElasticsearchReconciliationMode.class)
    void recoveryRemovesAssignmentsOfAttemptedUsersOutsidePreviousAndCurrentState(ElasticsearchReconciliationMode mode)
            throws Exception {
        var client = new FakeClient();
        client.users.put("alice", user("alice"));
        client.users.put("bob", user("bob", "attempted-role", "foreign"));
        var backend = backend(client, "require", mode);
        var previous = state(access("alice", "old-*", "read"));
        backend.apply(previous);
        var attempted = state(new Access("bob", new Resource("search", Map.of("cluster", "*")),
                Map.of("roles", Value.set(Set.of(Value.string("attempted-role"))))));
        var transaction = backend.prepareTransaction(previous, state(access("alice", "new-*", "write")), Set.of(attempted));
        transaction.apply();
        assertEquals(mode == ElasticsearchReconciliationMode.MANAGED ? Set.of("foreign") : Set.of(), client.rolesOf("bob"));
        transaction.rollback();
        assertEquals(mode == ElasticsearchReconciliationMode.MANAGED ? Set.of("foreign") : Set.of(), client.rolesOf("bob"));
        assertEquals(Set.of(roleName("alice")), client.rolesOf("alice"));
        assertTrue(client.roles.get(roleName("alice")).toString().contains("old-*"));
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void assignsOwnedRoleAndPreservesUnrelatedRolesThenRemovesOnlyItsRole()
            throws Exception {
        FakeClient client = new FakeClient();
        client.users.put("alice", user("alice", "other-role", "ops"));
        ElasticsearchBackend backend = backend(client, "require");
        PactState desired = state(access("alice", "logs-*", "read"));

        backend.prepareTransaction(PactState.empty(), desired).apply();
        String role = roleName("alice");
        assertTrue(client.roles.containsKey(role));
        assertTrue(client.roles.get(role).path("metadata")
                .path("pact_managed_by").asText().equals("pact"));
        assertEquals(Set.of("other-role", "ops", role), client.rolesOf("alice"));
        assertEquals("unchanged", client.users.get("alice")
                .path("metadata").path("note").asText());

        backend.prepareTransaction(desired, PactState.empty()).apply();
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

        backend.prepareTransaction(PactState.empty(), initial).apply();
        assertNotNull(client.users.get("ensure-user"));
        assertTrue(client.users.get("ensure-user").path("roles").isArray());
        assertFalse(client.users.get("ensure-user").has("password"));
        backend.prepareTransaction(initial, PactState.empty()).apply();
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
        backend.prepareTransaction(oldState, rotatedState).apply();
        assertEquals("new-password", client.passwordUpdates.get("alice"));

        client.passwordUpdates.clear();
        Identity sameVersion = identity("2", "not-applied");
        backend.prepareTransaction(
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
        creationBackend.prepareTransaction(
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
                backend.prepareTransaction(PactState.empty(), withoutEnsure));

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
                backend.prepareTransaction(PactState.empty(), desired));
        assertEquals("other", client.roles.get(roleName)
                .path("metadata").path("owner").asText());
    }

    @Test
    void refusesRoleThatBecomesUnmanagedAfterPreparation() throws Exception {
        FakeClient client = new FakeClient();
        client.users.put("alice", user("alice"));
        ElasticsearchBackend backend = backend(client, "require");
        PactState desired = state(access("alice", "logs-*", "read"));
        var transaction = backend.prepareTransaction(PactState.empty(), desired);

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
        var transaction = backend.prepareTransaction(PactState.empty(), desired);
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
        return backend(client, principalMode, ElasticsearchReconciliationMode.MANAGED);
    }

    private static ElasticsearchBackend backend(FakeClient client, String principalMode,
            ElasticsearchReconciliationMode mode) {
        return new ElasticsearchBackend(
                "search",
                new ElasticsearchConfig(
                        URI.create("http://localhost:9200"),
                        "pact",
                        "secret",
                        null,
                        principalMode, mode, null
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
        private int writes;
        private boolean closed;

        @Override public void close() { closed = true; }

        @Override public io.github.pactproject.elasticsearch.api.ElasticsearchPrivileges getPrivileges() {
            return new io.github.pactproject.elasticsearch.api.ElasticsearchPrivileges(Set.of("monitor", "manage_ilm"),
                    Set.of("read", "write", "view_index_metadata"));
        }

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
            writes++;
            roles.put(name, definition.deepCopy());
        }

        @Override
        public void deleteRole(String name) {
            writes++;
            roles.remove(name);
        }

        @Override
        public void putUser(String username, JsonNode definition)
                throws ElasticsearchClientException {
            writes++;
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
            writes++;
            passwordUpdates.put(username, password);
        }

        private Set<String> rolesOf(String username) {
            java.util.TreeSet<String> result = new java.util.TreeSet<>();
            users.get(username).path("roles")
                    .forEach(role -> result.add(role.asText()));
            return Set.copyOf(result);
        }
    }

    @Test
    void closeReleasesClientWithoutChangingRolesOrUsers() throws Exception {
        var client = new FakeClient();
        client.users.put("alice", user("alice", "manual"));
        var backend = backend(client, "require");
        backend.apply(state(access("alice", "logs-*", "read")));
        var roles = Map.copyOf(client.roles);
        var users = Map.copyOf(client.users);
        int writes = client.writes;
        backend.close();
        assertTrue(client.closed);
        assertEquals(writes, client.writes);
        assertEquals(roles, client.roles);
        assertEquals(users, client.users);
    }

    @Test
    void clusterPrivilegesAndExistingRolesAreMergedAndRevokedWithoutEditingSharedRoles() throws Exception {
        var client = new FakeClient();
        client.users.put("alice", user("alice", "external"));
        var shared = MAPPER.readTree("{\"cluster\":[\"monitor\"],\"metadata\":{\"owner\":\"ops\"}}");
        client.roles.put("monitoring_user", shared);
        var backend = backend(client, "require");
        var desired = state(access("alice", "logs-*", "read"), cluster("alice", "monitor", "monitoring_user"));
        var transaction = backend.prepareTransaction(PactState.empty(), desired);
        assertEquals(0, client.writes, "Preparation must be read-only");
        transaction.apply();
        assertEquals(Set.of("external", "monitoring_user", roleName("alice")), client.rolesOf("alice"));
        assertEquals("monitor", client.roles.get(roleName("alice")).path("cluster").get(0).asText());
        assertEquals(shared, client.roles.get("monitoring_user"));
        int writes = client.writes;
        backend.prepareTransaction(desired, desired).apply();
        assertEquals(writes, client.writes, "Repeated reconciliation must not write unchanged roles/users");
        backend.prepareTransaction(desired, PactState.empty()).apply();
        assertEquals(Set.of("external"), client.rolesOf("alice"));
        assertEquals(shared, client.roles.get("monitoring_user"));
    }

    @Test
    void authoritativeModeRestrictsOnlyDeclaredUsersAndRollbackReappliesEmptyPreviousState() throws Exception {
        var client = new FakeClient();
        client.users.put("alice", user("alice", "external", "mixed-cluster-and-index"));
        client.users.put("bob", user("bob", "external"));
        client.roles.put("monitoring_user", MAPPER.createObjectNode());
        var backend = backend(client, "require", ElasticsearchReconciliationMode.AUTHORITATIVE);
        var desired = state(cluster("alice", null, "monitoring_user"));
        var tx = backend.prepareTransaction(PactState.empty(), desired);
        tx.apply();
        assertEquals(Set.of("monitoring_user"), client.rolesOf("alice"));
        assertEquals(Set.of("external"), client.rolesOf("bob"));
        assertFalse(client.roles.containsKey(roleName("alice")), "Roles-only declarations need no empty generated role");
        tx.rollback();
        assertEquals(Set.of(), client.rolesOf("alice"));
        assertEquals(Set.of("external"), client.rolesOf("bob"));
        backend.prepareTransaction(PactState.empty(), desired).apply();
        backend.prepareTransaction(desired, PactState.empty()).apply();
        assertEquals(Set.of(), client.rolesOf("alice"));
        assertTrue(client.roles.containsKey("monitoring_user"));
    }

    @Test
    void rollbackRestoresDeclaredPrivilegesEvenIfApplyDidNotRewriteDriftedRole() throws Exception {
        var client = new FakeClient();
        client.users.put("alice", user("alice"));
        var backend = backend(client, "require", ElasticsearchReconciliationMode.AUTHORITATIVE);
        var previous = state(access("alice", "logs-*", "read"));
        backend.prepareTransaction(PactState.empty(), previous).apply();
        JsonNode expected = client.roles.get(roleName("alice")).deepCopy();
        var drifted = (ObjectNode) expected.deepCopy();
        ((ObjectNode) drifted.path("indices").get(0)).putArray("privileges").add("write");
        client.roles.put(roleName("alice"), drifted);
        client.users.put("alice", user("alice", roleName("alice"), "external"));
        var desired = state(access("alice", "logs-*", "write"));
        var transaction = backend.prepareTransaction(previous, desired);
        transaction.apply();
        transaction.rollback();
        assertEquals(expected, client.roles.get(roleName("alice")));
        assertEquals(Set.of(roleName("alice")), client.rolesOf("alice"));
    }

    @Test
    void identityOnlyCreatesAccountAndNeverResetsItsManualPassword() throws Exception {
        var client = new FakeClient();
        var backend = backend(client, "ensure");
        var desired = state(new Identity("search", "alice", true, null, null, null));
        var tx = backend.prepareTransaction(PactState.empty(), desired);
        tx.apply();
        assertEquals(Set.of(), client.rolesOf("alice"));
        assertTrue(client.roles.isEmpty());
        assertTrue(client.passwordsUsedOnCreate.get("alice").length() >= 40);
        int writes = client.writes;
        backend.prepareTransaction(desired, desired).apply();
        assertEquals(writes, client.writes);
        assertTrue(client.passwordUpdates.isEmpty());
        tx.rollback();
        assertNotNull(client.getUser("alice"), "Created accounts outlive rollback");
    }

    @Test
    void rejectsAllBadReferencesBeforeWritingAnyUserOrRole() throws Exception {
        var client = new FakeClient();
        client.users.put("alice", user("alice"));
        var backend = backend(client, "ensure");
        var ensure = new Identity("search", "new-user", true, null, null, null);
        for (Access bad : Set.of(access("alice", "logs-*", "not_a_privilege"),
                cluster("alice", "not_a_cluster_privilege"), cluster("alice", null, "missing_role"))) {
            assertThrows(ValidationException.class, () -> backend.prepareTransaction(PactState.empty(), state(ensure, bad)));
            assertEquals(0, client.writes);
            assertFalse(client.users.containsKey("new-user"));
        }
        assertThrows(ValidationException.class, () -> backend.prepareTransaction(PactState.empty(),
                state(new Identity("search", "alice", true, "ns/secret#password", "1", null))));
        assertEquals(0, client.writes);
    }

    @Test
    void rechecksDeletedRoleBeforeAnyWritesAndUnstartedRollbackDoesNothing() throws Exception {
        var client = new FakeClient();
        client.users.put("alice", user("alice", "external"));
        client.roles.put("monitoring_user", MAPPER.createObjectNode());
        var backend = backend(client, "require");
        var tx = backend.prepareTransaction(PactState.empty(), state(cluster("alice", "monitor", "monitoring_user")));
        client.roles.remove("monitoring_user");
        assertThrows(BackendOperationException.class, tx::apply);
        tx.rollback();
        assertEquals(0, client.writes);
        var unstarted = backend.prepareTransaction(PactState.empty(), state(access("alice", "logs-*", "read")));
        unstarted.rollback();
        assertEquals(0, client.writes);
    }

    @Test
    void managedRollbackPreservesForeignAssignmentsAddedAfterPrepare() throws Exception {
        var client = new FakeClient();
        client.users.put("alice", user("alice", "external"));
        client.roles.put("monitoring_user", MAPPER.createObjectNode());
        var tx = backend(client, "require").prepareTransaction(PactState.empty(), state(cluster("alice", "monitor", "monitoring_user")));
        tx.apply();
        ((ObjectNode) client.users.get("alice")).withArray("roles").add("concurrent-ops");
        tx.rollback();
        assertEquals(Set.of("external", "concurrent-ops"), client.rolesOf("alice"));
        assertFalse(client.roles.containsKey(roleName("alice")));
    }

    @Test
    void rejectsReservedUsersEvenInIdentityOnlyScope() {
        var client = new FakeClient();
        var reserved = user("elastic", "superuser");
        reserved.withObject("/metadata").put("_reserved", true);
        client.users.put("elastic", reserved);
        assertThrows(ValidationException.class, () -> backend(client, "ensure").prepareTransaction(PactState.empty(),
                state(new Identity("search", "elastic", true, null, null, null))));
        assertEquals(0, client.writes);
    }

    private static Access cluster(String principal, String privilege, String... roles) {
        Map<String, Value> attributes = new HashMap<>();
        if (privilege != null) attributes.put("permissions", Value.object(Map.of("cluster", Value.set(Set.of(Value.string(privilege))))));
        if (roles.length > 0) attributes.put("roles", Value.set(java.util.Arrays.stream(roles).map(Value::string).collect(java.util.stream.Collectors.toSet())));
        return new Access(principal, new Resource("search", Map.of("cluster", "*")), attributes);
    }
}
