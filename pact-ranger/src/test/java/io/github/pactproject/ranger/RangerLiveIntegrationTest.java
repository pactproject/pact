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
        RangerConfig config = RangerConfig.from(Map.of(
                "base-url", required("PACT_IT_RANGER_BASE_URL"),
                "username", required("PACT_IT_RANGER_USERNAME"),
                "password", required("PACT_IT_RANGER_PASSWORD"),
                "service-name", required("PACT_IT_RANGER_SERVICE_NAME")
        ));
        RangerHttpClient client = new RangerHttpClient(config);
        String serviceType = client.getServiceType(config.serviceName());
        RangerServiceDefinition definition = RangerServiceDefinition.parse(
                client.getServiceDefinition(serviceType),
                serviceType
        );
        RangerPolicySync sync = new RangerPolicySync(
                client,
                config.serviceName(),
                config.pageSize()
        );

        assertTrue(
                sync.getManagedPolicies().isEmpty(),
                "Use a dedicated Ranger service with no pre-existing managed policies"
        );

        String resourceName = required("PACT_IT_RANGER_RESOURCE");
        String username = required("PACT_IT_RANGER_TEST_USER");
        List<String> supportedAccessTypes = definition.accessTypes().stream()
                .filter(accessType -> supports(definition, resourceName, accessType))
                .sorted()
                .toList();
        assertFalse(
                supportedAccessTypes.isEmpty(),
                "Configured Ranger resource has no supported access types"
        );

        Map<String, String> target = new TreeMap<>();
        definition.ancestorsInclusive(resourceName).forEach(
                ancestor -> target.put(
                        ancestor,
                        "pact-it-" + ancestor + "-" + System.nanoTime()
                )
        );

        PactState initial = state(
                serviceType,
                resourceName,
                target,
                username,
                Set.of(supportedAccessTypes.get(0)),
                null
        );
        PactState updated = supportedAccessTypes.size() > 1
                ? state(
                        serviceType,
                        resourceName,
                        target,
                        username,
                        Set.of(
                                supportedAccessTypes.get(0),
                                supportedAccessTypes.get(1)
                        ),
                        null
                )
                : state(
                        serviceType,
                        resourceName,
                        target,
                        username,
                        Set.of(supportedAccessTypes.get(0)),
                        "pact-it-condition"
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
                    sync.getManagedPolicies(),
                    resourceName,
                    target.get(resourceName),
                    username,
                    Set.of(supportedAccessTypes.get(0))
            );

            BackendTransaction update = backend.prepare(initial, updated);
            transactions.add(update);
            update.apply();
            List<JsonNode> afterUpdate = sync.getManagedPolicies();
            Set<String> expectedUpdatedAccessTypes = supportedAccessTypes.size() > 1
                    ? Set.of(
                            supportedAccessTypes.get(0),
                            supportedAccessTypes.get(1)
                    )
                    : Set.of(supportedAccessTypes.get(0));
            assertPolicy(
                    afterUpdate,
                    resourceName,
                    target.get(resourceName),
                    username,
                    expectedUpdatedAccessTypes
            );

            BackendTransaction idempotent = backend.prepare(updated, updated);
            transactions.add(idempotent);
            idempotent.apply();
            assertEquals(afterUpdate, sync.getManagedPolicies());

            BackendTransaction delete = backend.prepare(updated, PactState.empty());
            transactions.add(delete);
            delete.apply();
            assertTrue(sync.getManagedPolicies().isEmpty());
        }
        finally {
            for (int index = transactions.size() - 1; index >= 0; index--) {
                transactions.get(index).rollback();
            }
        }

        assertTrue(
                sync.getManagedPolicies().isEmpty(),
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
            String resourceName,
            String resourceValue,
            String username,
            Set<String> expectedAccessTypes
    ) {
        assertEquals(1, policies.size());
        JsonNode policy = policies.get(0);
        assertEquals(
                resourceValue,
                policy.path("resources")
                        .path(resourceName)
                        .path("values")
                        .get(0)
                        .asText()
        );
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
