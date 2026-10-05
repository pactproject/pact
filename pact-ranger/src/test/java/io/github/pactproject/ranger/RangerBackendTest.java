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

class RangerBackendTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void rangerTransactionCanCompensateAnAppliedChange() throws Exception {
        FakeClient client = new FakeClient();
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

        BackendTransaction transaction = backend.prepare(
                PactState.empty(),
                desired
        );
        transaction.apply();
        assertEquals(1, client.policies.size());

        transaction.rollback();
        assertEquals(0, client.policies.size());
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

        backend.prepare(PactState.empty(), desired).apply();

        assertEquals(2, client.policies.size());
        assertTrue(client.policies.stream().anyMatch(policy ->
                policy.path("name").asText().equals("hand-written")));
    }

    private static final class FakeClient implements RangerClient {
        private final List<JsonNode> policies = new ArrayList<>();
        private final AtomicLong ids = new AtomicLong();

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
        public JsonNode createPolicy(ObjectNode policy) {
            ObjectNode saved = policy.deepCopy();
            saved.put("id", ids.incrementAndGet());
            policies.add(saved);
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
