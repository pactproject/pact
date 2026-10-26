package io.github.pactproject.ranger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.pactproject.api.Access;
import io.github.pactproject.api.BackendTransaction;
import io.github.pactproject.api.PactState;
import io.github.pactproject.api.Resource;
import io.github.pactproject.api.value.Value;
import io.github.pactproject.ranger.api.RangerClient;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RangerBackendTest {
    @Test
    void recoveryCleansServiceWhenCurrentAndAgreedDeclarationsAreEmpty() throws Exception {
        var client = new FakeClient();
        var backend = new RangerBackend("ozone", new RangerConfig(
                URI.create("http://localhost:6080/service"), "sync", "secret", "ozone-cluster", 100), client);
        var attempted = new PactState(Set.of(new Access("alice", new Resource("ozone", Map.of("volume", "warehouse")),
                Map.of("permissions", Value.object(Map.of("volume", Value.set(Set.of(Value.string("read")))))))));
        backend.apply(attempted);
        var transaction = backend.prepareTransaction(PactState.empty(), PactState.empty(), Set.of(attempted));
        assertEquals(1, client.policies.size());
        transaction.apply();
        assertTrue(client.policies.isEmpty());
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void rejectsExplicitIdentitiesBeforePolicySynchronization() {
        var client = new FakeClient();
        var backend = new RangerBackend("ozone", new RangerConfig(
                URI.create("http://localhost:6080/service"), "sync", "secret", "ozone-cluster", 100), client);
        var desired = new PactState(Set.of(), Set.of(new io.github.pactproject.api.Identity(
                "ozone", "alice", true, null, null, null)));
        assertThrows(io.github.pactproject.api.exception.BackendFeatureNotImplementedException.class,
                () -> backend.prepareTransaction(PactState.empty(), desired));
        assertThrows(io.github.pactproject.api.exception.BackendFeatureNotImplementedException.class,
                () -> backend.apply(desired));
        assertTrue(client.policies.isEmpty());
    }

    @Test
    void rangerRollbackRestoresDeclarationInsteadOfExternalPolicies() throws Exception {
        FakeClient client = new FakeClient();
        client.policies.add(MAPPER.readTree("""
                {"id":41,"name":"external-drift","policyLabels":["manual"]}
                """));
        RangerBackend backend = new RangerBackend(
                "ozone",
                new RangerConfig(
                        URI.create("http://localhost:6080/service"),
                        "sync",
                        "secret",
                        "ozone-cluster",
                        100
                ),
                client
        );
        PactState desired = new PactState(Set.of(
                new Access(
                        "alice",
                        new Resource("ozone", Map.of("volume", "warehouse")),
                        Map.of(
                                "permissions",
                                Value.object(Map.of(
                                        "volume",
                                        Value.set(Set.of(Value.string("read")))
                                ))
                        )
                )
        ));

        BackendTransaction transaction = backend.prepareTransaction(
                PactState.empty(),
                desired
        );
        transaction.apply();
        assertEquals(1, client.policies.size());

        transaction.rollback();
        assertEquals(0, client.policies.size());
    }

    @Test
    void rollbackAndRetryConvergeAfterCreateSucceedsButResponseIsLost() throws Exception {
        var client = new FakeClient();
        var backend = new RangerBackend("ozone", new RangerConfig(
                URI.create("http://localhost:6080/service"), "sync", "secret", "ozone-cluster", 100), client);
        var target = new Resource("ozone", Map.of("volume", "warehouse"));
        Map<String, Value> permissions = Map.of("permissions", Value.object(Map.of(
                "volume", Value.set(Set.of(Value.string("read"))))));
        var previous = new PactState(Set.of(new Access("alice", target, permissions)));
        var desired = new PactState(Set.of(new Access("bob",
                new Resource("ozone", Map.of("volume", "new-warehouse")), permissions)));
        backend.prepareTransaction(PactState.empty(), previous).apply();
        String previousPolicy = client.policies.get(0).path("name").asText();
        var transaction = backend.prepareTransaction(previous, desired);
        assertEquals(1, client.policies.size(), "Preparation must not write policies");
        client.failAfterCreate = true;

        assertThrows(io.github.pactproject.api.exception.BackendException.class, transaction::apply);
        assertEquals(2, client.policies.size(), "The server persisted the failed create");
        transaction.rollback();
        assertEquals(List.of(previousPolicy), client.policies.stream()
                .map(policy -> policy.path("name").asText()).toList());
        backend.prepareTransaction(previous, desired).apply();
        assertEquals(1, client.policies.size());
        assertTrue(!previousPolicy.equals(client.policies.get(0).path("name").asText()));
        assertEquals("bob", client.policies.get(0).path("policyItems").get(0).path("users").get(0).asText());
    }

    @Test
    void backendUsesManagedOnlyScopeWhenConfigured() throws Exception {
        FakeClient client = new FakeClient();
        client.policies.add(MAPPER.readTree("""
                {"id":1,"name":"hand-written","policyLabels":["manual"]}
                """));
        RangerBackend backend = new RangerBackend(
                "ozone",
                new RangerConfig(
                        URI.create("http://localhost:6080/service"),
                        "sync",
                        "secret",
                        "ozone-cluster",
                        100,
                        true
                ),
                client
        );
        PactState desired = new PactState(Set.of(
                new Access(
                        "alice",
                        new Resource("ozone", Map.of("volume", "warehouse")),
                        Map.of(
                                "permissions",
                                Value.object(Map.of(
                                        "volume",
                                        Value.set(Set.of(Value.string("read")))
                                ))
                        )
                )
        ));

        backend.prepareTransaction(PactState.empty(), desired).apply();

        assertEquals(2, client.policies.size());
        assertTrue(client.policies.stream().anyMatch(policy ->
                policy.path("name").asText().equals("hand-written")));
    }

    private static final class FakeClient implements RangerClient {
        private final List<JsonNode> policies = new ArrayList<>();
        private final AtomicLong ids = new AtomicLong();
        private boolean failAfterCreate;

        @Override
        public void ensureUser(String username) {
        }

        @Override
        public String getServiceType(String serviceName) {
            assertEquals("ozone-cluster", serviceName);
            return "ozone";
        }

        @Override
        public JsonNode getServiceDefinition(String serviceType)
                throws io.github.pactproject.ranger.api.RangerClientException {
            try {
                return MAPPER.readTree("""
                        {
                          "name":"ozone",
                          "resources":[{"name":"volume"}],
                          "accessTypes":[{"name":"read"}]
                        }
                        """);
            }
            catch (Exception e) {
                throw new io.github.pactproject.ranger.api.RangerClientException(
                        "invalid fixture",
                        e
                );
            }
        }

        @Override
        public List<JsonNode> getPolicies(String serviceName, int pageSize) {
            return List.copyOf(policies);
        }

        @Override
        public JsonNode createPolicy(ObjectNode policy)
                throws io.github.pactproject.ranger.api.RangerClientException {
            ObjectNode saved = policy.deepCopy();
            saved.put("id", ids.incrementAndGet());
            policies.add(saved);
            if (failAfterCreate) {
                failAfterCreate = false;
                throw new io.github.pactproject.ranger.api.RangerClientException("Lost create response");
            }
            return saved;
        }

        @Override
        public JsonNode updatePolicy(long id, ObjectNode policy) {
            ObjectNode saved = policy.deepCopy();
            saved.put("id", id);
            for (int i = 0; i < policies.size(); i++) {
                if (policies.get(i).path("id").asLong() == id) {
                    policies.set(i, saved);
                    return saved;
                }
            }
            throw new AssertionError("Unknown policy id");
        }

        @Override
        public void deletePolicy(long id) {
            policies.removeIf(policy -> policy.path("id").asLong() == id);
        }
    }
}
