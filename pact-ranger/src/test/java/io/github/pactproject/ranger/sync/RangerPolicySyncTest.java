package io.github.pactproject.ranger.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.pactproject.ranger.api.RangerClient;
import io.github.pactproject.ranger.api.RangerClientException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RangerPolicySyncTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void onlyManagedPoliciesAreChangedAndEqualPoliciesAreStable()
            throws Exception {
        FakeClient client = new FakeClient();
        client.policies.add(MAPPER.readTree("""
                {"id":1,"name":"old-managed","policyLabels":["managed"]}
                """));
        client.policies.add(MAPPER.readTree("""
                {"id":2,"name":"hand-written","policyLabels":["manual"]}
                """));
        RangerPolicySync sync = new RangerPolicySync(client, "service", 20);
        ObjectNode desired = (ObjectNode) MAPPER.readTree("""
                {
                  "name":"new-managed",
                  "service":"service",
                  "serviceType":"ozone",
                  "policyLabels":["managed"],
                  "policyItems":[{"users":["alice"],"accesses":[]}],
                  "resources":{"volume":{"values":["data"],"isExcludes":false,"isRecursive":false}}
                }
                """);

        List<JsonNode> snapshot = sync.getManagedPolicies();
        sync.synchronize(snapshot, List.of(desired));

        assertEquals(2, client.policies.size());
        assertEquals("hand-written", client.policies.get(0).path("name").asText());
        assertEquals(1, client.created);
        assertEquals(1, client.deleted);
        assertEquals(List.of("alice"), client.ensuredUsers);
        assertEquals(
                List.of("ensure:alice", "create:new-managed", "delete:1"),
                client.operations
        );

        List<JsonNode> current = sync.getManagedPolicies();
        sync.synchronize(current, List.of(desired));
        assertEquals(1, client.created);
        assertEquals(0, client.updated);
        assertEquals(List.of("alice"), client.ensuredUsers);
        assertTrue(client.policies.stream()
                .anyMatch(policy -> policy.path("name").asText().equals("hand-written")));
    }

    private static final class FakeClient implements RangerClient {
        private final List<JsonNode> policies = new ArrayList<>();
        private final AtomicLong ids = new AtomicLong(10);
        private int created;
        private int updated;
        private int deleted;
        private final List<String> ensuredUsers = new ArrayList<>();
        private final List<String> operations = new ArrayList<>();

        @Override
        public void ensureUser(String username) {
            ensuredUsers.add(username);
            operations.add("ensure:" + username);
        }

        @Override
        public String getServiceType(String serviceName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public JsonNode getServiceDefinition(String serviceType) {
            throw new UnsupportedOperationException();
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
            created++;
            operations.add("create:" + policy.path("name").asText());
            return saved;
        }

        @Override
        public JsonNode updatePolicy(long id, ObjectNode policy) {
            for (int i = 0; i < policies.size(); i++) {
                if (policies.get(i).path("id").asLong() == id) {
                    ObjectNode saved = policy.deepCopy();
                    saved.put("id", id);
                    policies.set(i, saved);
                    updated++;
                    return saved;
                }
            }
            throw new AssertionError("Unknown policy id: " + id);
        }

        @Override
        public void deletePolicy(long id) {
            policies.removeIf(policy -> policy.path("id").asLong() == id);
            deleted++;
            operations.add("delete:" + id);
        }
    }
}
