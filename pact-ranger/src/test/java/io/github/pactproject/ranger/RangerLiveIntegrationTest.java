package io.github.pactproject.ranger;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.pactproject.api.Access;
import io.github.pactproject.api.BackendTransaction;
import io.github.pactproject.api.PactState;
import io.github.pactproject.api.Resource;
import io.github.pactproject.api.value.Value;
import io.github.pactproject.ranger.client.RangerHttpClient;
import io.github.pactproject.ranger.compile.RangerServiceDefinition;
import io.github.pactproject.ranger.sync.RangerPolicySync;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "pact.integration", matches = "true")
class RangerLiveIntegrationTest {
    @Test
    void discoversServiceDefinitionAndReconcilesPolicies() throws Exception {
        reconcileService(
                "hdfs",
                required("PACT_IT_RANGER_SERVICE_NAME"),
                required("PACT_IT_RANGER_RESOURCE"),
                required("PACT_IT_RANGER_TEST_USER"),
                null
        );
    }

    @Test
    void reconcilesOzoneKeyPolicies() throws Exception {
        reconcileService(
                "ozone",
                required("PACT_IT_RANGER_OZONE_SERVICE_NAME"),
                "key",
                required("PACT_IT_RANGER_TEST_USER"),
                List.of("read", "write")
        );
    }

    @Test
    void reconcilesTrinoTablePolicies() throws Exception {
        reconcileService(
                "trino",
                required("PACT_IT_RANGER_TRINO_SERVICE_NAME"),
                "table",
                required("PACT_IT_RANGER_TEST_USER"),
                List.of("select", "insert")
        );
    }

    @Test
    void reconcilesKafkaTopicPolicies() throws Exception {
        reconcileService(
                "kafka",
                required("PACT_IT_RANGER_KAFKA_SERVICE_NAME"),
                "topic",
                required("PACT_IT_RANGER_TEST_USER"),
                List.of("publish", "consume")
        );
    }

    private static void reconcileService(
            String expectedServiceType,
            String serviceName,
            String resourceName,
            String username,
            List<String> requestedAccessTypes
    ) throws Exception {
        RangerConfig config = RangerConfig.from(Map.of(
                "base-url", required("PACT_IT_RANGER_BASE_URL"),
                "username", required("PACT_IT_RANGER_USERNAME"),
                "password", required("PACT_IT_RANGER_PASSWORD"),
                "service-name", serviceName,
                "managed-only", "true"
        ));
        RangerHttpClient client = new RangerHttpClient(config);
        String serviceType = client.getServiceType(config.serviceName());
        assertEquals(
                expectedServiceType,
                serviceType,
                "Configured Ranger service must use the expected ServiceDef"
        );
        RangerServiceDefinition definition = RangerServiceDefinition.parse(
                client.getServiceDefinition(serviceType),
                serviceType
        );
        RangerPolicySync sync = new RangerPolicySync(
                client,
                config.serviceName(),
                config.pageSize(),
                config.managedOnly()
        );

        assertTrue(
                sync.getPoliciesInScope().isEmpty(),
                "Use a dedicated Ranger service with no pre-existing managed policies"
        );

        List<String> supportedAccessTypes = definition.accessTypes().stream()
                .filter(accessType -> supports(definition, resourceName, accessType))
                .sorted()
                .toList();
        assertFalse(
                supportedAccessTypes.isEmpty(),
                "Configured Ranger resource has no supported access types"
        );
        List<String> accessTypes = requestedAccessTypes == null
                ? supportedAccessTypes.subList(
                        0,
                        Math.min(2, supportedAccessTypes.size())
                )
                : requestedAccessTypes;
        for (String accessType : accessTypes) {
            assertTrue(
                    supports(definition, resourceName, accessType),
                    "Ranger resource " + resourceName
                            + " does not support access type " + accessType
            );
        }

        Map<String, String> target = new TreeMap<>();
        definition.ancestorsInclusive(resourceName).forEach(
                ancestor -> target.put(
                        ancestor,
                        "pact-it-" + expectedServiceType + "-" + ancestor
                                + "-" + System.nanoTime()
                )
        );

        PactState initial = state(
                serviceType,
                resourceName,
                target,
                username,
                Set.of(accessTypes.get(0)),
                null
        );
        PactState updated = state(
                serviceType,
                resourceName,
                target,
                username,
                Set.copyOf(accessTypes),
                accessTypes.size() > 1 ? null : "pact-it-condition"
        );

        RangerBackend backend = new RangerBackend(
                serviceType,
                config,
                client
        );
        List<BackendTransaction> transactions = new ArrayList<>();
        try {
            BackendTransaction create = backend.prepare(PactState.empty(), initial);
            transactions.add(create);
            create.apply();
            assertPolicy(
                    sync.getPoliciesInScope(),
                    serviceName,
                    serviceType,
                    target,
                    username,
                    Set.of(accessTypes.get(0))
            );

            BackendTransaction update = backend.prepare(initial, updated);
            transactions.add(update);
            update.apply();
            List<JsonNode> afterUpdate = sync.getPoliciesInScope();
            assertPolicy(
                    afterUpdate,
                    serviceName,
                    serviceType,
                    target,
                    username,
                    Set.copyOf(accessTypes)
            );

            BackendTransaction idempotent = backend.prepare(updated, updated);
            transactions.add(idempotent);
            idempotent.apply();

            List<JsonNode> afterIdempotent = sync.getPoliciesInScope();
            assertEquals(1, afterIdempotent.size());
            JsonNode idempotentPolicy = afterIdempotent.get(0);
            JsonNode updatedPolicy = afterUpdate.get(0);
            assertEquals(
                    updatedPolicy.path("name").asText(),
                    idempotentPolicy.path("name").asText()
            );
            assertEquals(
                    updatedPolicy.path("resources").toString(),
                    idempotentPolicy.path("resources").toString()
            );
            assertEquals(
                    updatedPolicy.path("policyItems").toString(),
                    idempotentPolicy.path("policyItems").toString()
            );
            assertEquals(
                    updatedPolicy.path("conditions").toString(),
                    idempotentPolicy.path("conditions").toString()
            );

            BackendTransaction delete = backend.prepare(updated, PactState.empty());
            transactions.add(delete);
            delete.apply();
            assertTrue(sync.getPoliciesInScope().isEmpty());
        }
        finally {
            for (int index = transactions.size() - 1; index >= 0; index--) {
                transactions.get(index).rollback();
            }
        }

        assertTrue(
                sync.getPoliciesInScope().isEmpty(),
                "Integration test must restore the dedicated Ranger service"
        );
    }

    private static boolean supports(
            RangerServiceDefinition definition,
            String resource,
            String accessType
    ) {
        try {
            definition.validateAccessType(resource, accessType);
            return true;
        }
        catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static PactState state(
            String serviceType,
            String resourceName,
            Map<String, String> target,
            String username,
            Set<String> permissions,
            String condition
    ) {
        Map<String, Value> attributes = new HashMap<>();
        attributes.put(
                "permissions",
                Value.object(Map.of(
                        resourceName,
                        Value.set(permissions.stream()
                                .map(Value::string)
                                .collect(java.util.stream.Collectors.toSet()))
                ))
        );
        if (condition != null) {
            attributes.put(
                    "conditions",
                    Value.set(Set.of(Value.string(condition)))
            );
        }
        return new PactState(Set.of(
                new Access(
                        username,
                        new Resource(serviceType, target),
                        attributes
                )
        ));
    }

    private static void assertPolicy(
            List<JsonNode> policies,
            String serviceName,
            String serviceType,
            Map<String, String> expectedResources,
            String username,
            Set<String> expectedAccessTypes
    ) {
        assertEquals(1, policies.size());
        JsonNode policy = policies.get(0);
        assertEquals(serviceName, policy.path("service").asText());
        assertEquals(serviceType, policy.path("serviceType").asText());
        boolean managedLabel = false;
        for (JsonNode label : policy.path("policyLabels")) {
            managedLabel |= "managed".equals(label.asText());
        }
        assertTrue(managedLabel, "Expected the policy to carry PACT's managed label");
        Set<String> actualResourceNames = new java.util.HashSet<>();
        policy.path("resources").fieldNames()
                .forEachRemaining(actualResourceNames::add);
        assertEquals(expectedResources.keySet(), actualResourceNames);
        expectedResources.forEach((resourceName, value) -> {
            JsonNode values = policy.path("resources")
                    .path(resourceName)
                    .path("values");
            assertEquals(1, values.size(), "Expected one " + resourceName);
            assertEquals(value, values.get(0).asText());
        });
        Set<String> actualAccessTypes = new java.util.HashSet<>();
        boolean containsUser = false;
        for (JsonNode item : policy.path("policyItems")) {
            for (JsonNode user : item.path("users")) {
                if (username.equals(user.asText())) {
                    containsUser = true;
                }
            }
            for (JsonNode access : item.path("accesses")) {
                actualAccessTypes.add(access.path("type").asText());
            }
        }
        assertTrue(containsUser, "Expected managed policy to reference the test user");
        assertEquals(expectedAccessTypes, actualAccessTypes);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Missing required integration-test environment variable: "
                            + name
            );
        }
        return value;
    }
}
