package io.github.pactproject.kubernetes;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import io.github.pactproject.api.Access;
import io.github.pactproject.api.PactState;
import io.github.pactproject.api.StateReconciler;
import io.github.pactproject.api.exception.StateReconciliationException;
import io.github.pactproject.api.value.ObjectValue;
import io.github.pactproject.api.value.SetValue;
import io.github.pactproject.api.value.Value;
import io.github.pactproject.kubernetes.config.KubernetesConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "pact.integration", matches = "true")
class KubernetesLiveIntegrationTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Test
    void preservesGenericFieldsAndProcessesTheFullResourceLifecycle()
            throws Exception {
        String namespace = required("PACT_IT_K8S_NAMESPACE");
        KubernetesConfig config = new KubernetesConfig(
                required("PACT_IT_K8S_GROUP"),
                optional("PACT_IT_K8S_VERSION", "v1"),
                optional("PACT_IT_K8S_PLURAL", "dataaccesses")
        );
        KubernetesClient client = new KubernetesClientBuilder().build();
        ResourceDefinitionContext context =
                new ResourceDefinitionContext.Builder()
                        .withGroup(config.group())
                        .withVersion(config.version())
                        .withPlural(config.plural())
                        .withKind("DataAccess")
                        .withNamespaced(true)
                        .build();
        assertTrue(
                client.genericKubernetesResources(context)
                        .inAnyNamespace()
                        .list()
                        .getItems()
                        .isEmpty(),
                "Use a dedicated test CRD with no existing DataAccess objects"
        );
        String name = "pact-it-" + UUID.randomUUID().toString().substring(0, 8);
        String finalizer = config.group() + "/data-access";
        Map<String, Object> spec = Map.of(
                "resources",
                List.of(Map.of(
                        "customRangerService",
                        Map.of(
                                "namespace", "integration",
                                "resource", "object-" + name,
                                "customLocationAttribute", "preserve-me"
                        ),
                        "access",
                        List.of(Map.of(
                                "users", List.of("pact-it-user"),
                                "permissions", Map.of(
                                        "resource", List.of("inspect")
                                )
                        ))
                ))
        );
        GenericKubernetesResource resource = new GenericKubernetesResource();
        resource.setApiVersion(config.group() + "/" + config.version());
        resource.setKind("DataAccess");
        resource.setMetadata(new ObjectMetaBuilder()
                .withName(name)
                .withNamespace(namespace)
                .build());
        resource.setAdditionalProperty("spec", spec);

        RecordingReconciler reconciler = new RecordingReconciler();
        KubernetesStateProvider provider =
                new KubernetesStateProvider(client, config);
        boolean created = false;
        try {
            provider.start(reconciler);
            client.genericKubernetesResources(context)
                    .inNamespace(namespace)
                    .resource(resource)
                    .create();
            created = true;

            GenericKubernetesResource ready = awaitResource(
                    client,
                    context,
                    namespace,
                    name,
                    candidate -> finalizers(candidate).contains(finalizer)
                            && "Ready".equals(status(candidate).get("phase"))
            );
            assertNotNull(ready);
            assertEquals(spec, status(ready).get("lastAppliedSpec"));

            PactState applied = reconciler.awaitStateSize(1);
            Access access = applied.accesses().iterator().next();
            assertEquals("customRangerService", access.resource().backendId());
            assertEquals(
                    "preserve-me",
                    access.resource().target().get("customLocationAttribute")
            );
            ObjectValue permissions = org.junit.jupiter.api.Assertions.assertInstanceOf(
                    ObjectValue.class,
                    access.attributes().get("permissions")
            );
            SetValue resourcePermissions = org.junit.jupiter.api.Assertions.assertInstanceOf(
                    SetValue.class,
                    permissions.values().get("resource")
            );
            assertTrue(resourcePermissions.values().contains(Value.string("inspect")));

            provider.close();
            client = new KubernetesClientBuilder().build();
            RecordingReconciler restartedReconciler = new RecordingReconciler();
            provider = new KubernetesStateProvider(client, config);
            provider.start(restartedReconciler);
            assertEquals(applied, restartedReconciler.awaitStateSize(1));

            client.genericKubernetesResources(context)
                    .inNamespace(namespace)
                    .withName(name)
                    .delete();
            awaitResourceMissing(client, context, namespace, name);
            created = false;
            assertTrue(restartedReconciler.awaitStateSize(0).accesses().isEmpty());
        }
        finally {
            try {
                if (created) {
                    client.genericKubernetesResources(context)
                            .inNamespace(namespace)
                            .withName(name)
                            .delete();
                    awaitResourceMissing(client, context, namespace, name);
                }
            }
            finally {
                provider.close();
            }
        }
    }

    private static GenericKubernetesResource awaitResource(
            KubernetesClient client,
            ResourceDefinitionContext context,
            String namespace,
            String name,
            java.util.function.Predicate<GenericKubernetesResource> predicate
    ) throws InterruptedException {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            GenericKubernetesResource current = client
                    .genericKubernetesResources(context)
                    .inNamespace(namespace)
                    .withName(name)
                    .get();
            if (current != null && predicate.test(current)) {
                return current;
            }
            Thread.sleep(100L);
        }
        throw new AssertionError(
                "Timed out waiting for DataAccess " + namespace + "/" + name
        );
    }

    private static void awaitResourceMissing(
            KubernetesClient client,
            ResourceDefinitionContext context,
            String namespace,
            String name
    ) throws InterruptedException {
        await(() -> client.genericKubernetesResources(context)
                .inNamespace(namespace)
                .withName(name)
                .get() == null, "DataAccess deletion");
    }

    private static void await(
            BooleanSupplier condition,
            String description
    ) throws InterruptedException {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100L);
        }
        throw new AssertionError("Timed out waiting for " + description);
    }

    private static Map<String, ?> status(GenericKubernetesResource resource) {
        Object status = resource.getAdditionalProperties().get("status");
        if (!(status instanceof Map<?, ?> map)) {
            return Map.of();
        }
        return map.entrySet().stream()
                .filter(entry -> entry.getKey() instanceof String)
                .collect(java.util.stream.Collectors.toMap(
                        entry -> (String) entry.getKey(),
                        Map.Entry::getValue
                ));
    }

    private static List<String> finalizers(GenericKubernetesResource resource) {
        if (resource.getMetadata() == null
                || resource.getMetadata().getFinalizers() == null) {
            return List.of();
        }
        return resource.getMetadata().getFinalizers();
    }

    private static String optional(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
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

    private static final class RecordingReconciler implements StateReconciler {
        private volatile PactState state = PactState.empty();

        @Override
        public void restoreAppliedState(PactState restoredState) {
            state = restoredState;
        }

        @Override
        public void apply(PactState desiredState)
                throws StateReconciliationException {
            state = desiredState;
        }

        private PactState awaitStateSize(int expectedSize)
                throws InterruptedException {
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            while (System.nanoTime() < deadline) {
                PactState current = state;
                if (current.accesses().size() == expectedSize) {
                    return current;
                }
                Thread.sleep(100L);
            }
            throw new AssertionError(
                    "Timed out waiting for " + expectedSize
                            + " applied DataAccess accesses"
            );
        }
    }
}
