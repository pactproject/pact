package io.github.pactproject.kubernetes;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.github.pactproject.api.exception.BackendOperationException;
import io.github.pactproject.api.Identity;
import io.github.pactproject.api.PactState;
import io.github.pactproject.api.exception.StateProviderException;
import io.github.pactproject.api.exception.StateReconciliationException;
import io.github.pactproject.api.StateReconciler;
import io.github.pactproject.kubernetes.compile.DataAccessCompiler;
import io.github.pactproject.kubernetes.config.KubernetesConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnableKubernetesMockClient(crud = true)
class KubernetesStateProviderTest
{
    private static final ResourceDefinitionContext DATA_ACCESS =
            new ResourceDefinitionContext.Builder()
                    .withGroup("com.example.ru")
                    .withVersion("v1")
                    .withPlural("dataaccesses")
                    .withKind("DataAccess")
                    .withNamespaced(true)
                    .build();

    private KubernetesClient kubernetesClient;

    @Test
    void loadsDataAccessResources() throws StateProviderException {
        createDataAccess(
                "default",
                "alice-access",
                Map.of(
                        "resources",
                        List.of(
                                Map.of(
                                        "registry",
                                        Map.of(
                                                "name",
                                                "repository-a"
                                        ),
                                        "access",
                                        List.of(
                                                Map.of(
                                                        "users",
                                                        List.of("alice"),
                                                        "permissions",
                                                        List.of("read")
                                                )
                                        )
                                )
                        )
                )
        );

        KubernetesStateProvider provider =
                new KubernetesStateProvider(kubernetesClient);

        var state = provider.load();

        assertEquals(1, state.accesses().size());

        var access =
                state.accesses()
                        .iterator()
                        .next();

        assertEquals(
                "alice",
                access.principal()
        );

        assertEquals(
                "registry",
                access.resource().backendId()
        );

        assertEquals(
                "repository-a",
                access.resource()
                        .target()
                        .get("repository")
        );
    }

    @Test
    void loadsDataAccessResourcesFromAllNamespaces() throws StateProviderException {
        createDataAccess(
                "namespace-a",
                "alice-access",
                Map.of(
                        "resources",
                        List.of(
                                Map.of(
                                        "registry",
                                        Map.of(
                                                "name",
                                                "repository-a"
                                        ),
                                        "access",
                                        List.of(
                                                Map.of(
                                                        "users",
                                                        List.of("alice"),
                                                        "permissions",
                                                        List.of("read")
                                                )
                                        )
                                )
                        )
                )
        );

        createDataAccess(
                "namespace-b",
                "bob-access",
                Map.of(
                        "resources",
                        List.of(
                                Map.of(
                                        "registry",
                                        Map.of(
                                                "name",
                                                "repository-b"
                                        ),
                                        "access",
                                        List.of(
                                                Map.of(
                                                        "users",
                                                        List.of("bob"),
                                                        "permissions",
                                                        List.of("write")
                                                )
                                        )
                                )
                        )
                )
        );

        KubernetesStateProvider provider =
                new KubernetesStateProvider(kubernetesClient);

        var state = provider.load();

        assertEquals(2, state.accesses().size());
    }

    @Test
    void watchesResourcesAndAppliesRegistryPermissions()
            throws Exception
    {
        var reconciler = new RecordingReconciler();
        var provider = new KubernetesStateProvider(kubernetesClient);
        Map<String, ?> spec = registrySpec("alice", "read");

        provider.start(reconciler);
        createDataAccess("default", "watched", spec);

        GenericKubernetesResource updated =
                awaitDataAccess("default", "watched", resource ->
                        hasFinalizer(resource)
                                && statusPhase(resource).equals("Ready"));

        awaitAppliedStateCount(reconciler, 1);
        assertEquals(1, reconciler.appliedStates.size());
        PactState expected = new DataAccessCompiler().compile(List.of(spec));
        assertTrue(reconciler.appliedStates.stream()
                .allMatch(expected::equals));
        assertEquals(
                spec,
                updated.getAdditionalProperties()
                        .get("status") instanceof Map<?, ?> status
                        ? status.get("lastAppliedSpec")
                        : null
        );
        provider.close();
    }

    @Test
    void restoresLastAppliedSnapshotBeforeInformerEvents()
            throws Exception
    {
        var reconciler = new RecordingReconciler();
        var provider = new KubernetesStateProvider(kubernetesClient);
        Map<String, ?> spec = registrySpec("alice", "read");
        GenericKubernetesResource resource = dataAccess(
                "default",
                "restored",
                spec
        );
        resource.setAdditionalProperty(
                "status",
                Map.of(
                        "phase", "Ready",
                        "observedGeneration", 1,
                        "lastAppliedSpec", spec
                )
        );
        kubernetesClient.genericKubernetesResources(DATA_ACCESS)
                .inNamespace("default")
                .resource(resource)
                .create();

        provider.start(reconciler);
        awaitDataAccess("default", "restored", candidate ->
                hasFinalizer(candidate)
                        && statusPhase(candidate).equals("Ready"));

        awaitAppliedStateCount(reconciler, 1);
        assertEquals(
                new DataAccessCompiler().compile(List.of(spec)),
                reconciler.restoredState
        );
        assertTrue(reconciler.appliedStates.stream()
                .allMatch(reconciler.restoredState::equals));
        provider.close();
    }

    @Test
    void resolvesPasswordSecretTransientlyAndRestoresOnlyItsVersion()
            throws Exception
    {
        var reconciler = new RecordingReconciler();
        var provider = new KubernetesStateProvider(kubernetesClient);
        Map<String, ?> spec = identitySpec(false);
        String secretVersion = createSecret(
                "default",
                "app-credentials",
                "password",
                "s3cret"
        );

        provider.start(reconciler);
        createDataAccess("default", "identity", spec);
        GenericKubernetesResource applied = awaitDataAccess(
                "default",
                "identity",
                resource -> statusPhase(resource).equals("Ready")
        );

        Identity identity = reconciler.appliedStates.getFirst()
                .identities()
                .iterator()
                .next();
        assertEquals("s3cret", identity.password().reveal());
        assertEquals(secretVersion, identity.passwordVersion());
        assertEquals(
                "default/app-credentials/password",
                identity.passwordSource()
        );
        assertEquals(spec, status(applied).get("lastAppliedSpec"));
        assertTrue(!status(applied).toString().contains("s3cret"));

        var restoreReconciler = new RecordingReconciler();
        var restarted = new KubernetesStateProvider(kubernetesClient);
        restarted.start(restoreReconciler);

        Identity restored = restoreReconciler.restoredState
                .identities()
                .iterator()
                .next();
        assertEquals(secretVersion, restored.passwordVersion());
        assertEquals(null, restored.password());
        assertTrue(!restoreReconciler.restoredState.toString().contains("s3cret"));
        restarted.close();
        provider.close();
    }

    @Test
    void secretChangesTriggerReconciliationDuringInformerResync()
            throws Exception
    {
        var reconciler = new RecordingReconciler();
        var provider = new KubernetesStateProvider(
                kubernetesClient,
                new KubernetesConfig(
                        "com.example.ru",
                        "v1",
                        "dataaccesses",
                        100L,
                        10L,
                        List.of(10L)
                )
        );
        createSecret("default", "app-credentials", "password", "first");
        provider.start(reconciler);
        createDataAccess("default", "identity", identitySpec(false));
        GenericKubernetesResource initial = awaitDataAccess(
                "default",
                "identity",
                resource -> statusPhase(resource).equals("Ready")
        );
        awaitAppliedStateCount(reconciler, 1);

        String rotatedVersion = replaceSecret(
                "default",
                "app-credentials",
                "password",
                "rotated"
        );

        GenericKubernetesResource applied = awaitDataAccess(
                "default",
                "identity",
                candidate -> statusPhase(candidate).equals("Ready")
                        && lastIdentitySecretVersion(candidate)
                        .equals(rotatedVersion)
        );
        awaitAppliedStateCount(reconciler, 2);
        Identity identity = reconciler.appliedStates.getLast()
                .identities()
                .iterator()
                .next();
        assertEquals("rotated", identity.password().reveal());
        assertEquals(rotatedVersion, identity.passwordVersion());
        assertEquals(
                initial.getMetadata().getGeneration(),
                applied.getMetadata().getGeneration()
        );
        assertTrue(!status(applied).toString().contains("rotated"));

        kubernetesClient.secrets()
                .inNamespace("default")
                .withName("app-credentials")
                .delete();
        GenericKubernetesResource secretMissing = awaitDataAccess(
                "default",
                "identity",
                candidate -> statusPhase(candidate).equals("Error")
        );
        assertTrue(status(secretMissing).get("lastError").toString()
                .contains("does not exist"));
        assertEquals(2, reconciler.appliedStates.size());

        String restoredVersion = createSecret(
                "default",
                "app-credentials",
                "password",
                "restored"
        );
        awaitDataAccess(
                "default",
                "identity",
                candidate -> statusPhase(candidate).equals("Ready")
                        && lastIdentitySecretVersion(candidate)
                        .equals(restoredVersion)
        );
        awaitAppliedStateCount(reconciler, 3);
        assertEquals(
                "restored",
                reconciler.appliedStates.getLast().identities()
                        .iterator()
                        .next()
                        .password()
                        .reveal()
        );
        provider.close();
    }

    @Test
    void missingPasswordSecretMarksDataAccessErrorWithoutApplying()
            throws Exception
    {
        var reconciler = new RecordingReconciler();
        var provider = new KubernetesStateProvider(kubernetesClient);
        provider.start(reconciler);
        createDataAccess(
                "default",
                "missing-identity-secret",
                Map.of(
                        "resources", List.of(),
                        "identities", List.of(Map.of(
                                "backend", "postgres",
                                "name", "app",
                                "passwordSecretRef", Map.of(
                                        "name", "missing",
                                        "key", "password"
                                )
                        ))
                )
        );

        GenericKubernetesResource failed = awaitDataAccess(
                "default",
                "missing-identity-secret",
                resource -> statusPhase(resource).equals("Error")
        );
        assertTrue(status(failed).get("lastError").toString()
                .contains("does not exist"));
        assertTrue(reconciler.appliedStates.isEmpty());
        assertTrue(!status(failed).toString().contains("password"));
        provider.close();
    }

    @Test
    void rejectsIdentityDeclaredByMultipleDataAccessResources()
    {
        Map<String, ?> spec = Map.of(
                "resources", List.of(),
                "identities", List.of(Map.of(
                        "backend", "postgres",
                        "name", "app"
                ))
        );
        createDataAccess("default", "identity-a", spec);
        createDataAccess("default", "identity-b", spec);
        KubernetesStateProvider provider =
                new KubernetesStateProvider(kubernetesClient);

        assertThrows(StateProviderException.class, provider::load);
    }

    private Map<String, ?> identitySpec(boolean ensure)
    {
        return Map.of(
                "resources", List.of(),
                "identities", List.of(Map.of(
                        "backend", "postgres",
                        "name", "app",
                        "ensure", ensure,
                        "passwordSecretRef", Map.of(
                                "name", "app-credentials",
                                "key", "password"
                        )
                ))
        );
    }

    @Test
    void validationFailureSetsErrorWithoutApplying()
            throws Exception
    {
        var reconciler = new RecordingReconciler();
        var provider = new KubernetesStateProvider(kubernetesClient);
        Map<String, ?> invalidSpec = Map.of(
                "resources",
                List.of(
                        Map.of(
                                "registry",
                                Map.of("name", "repository-a"),
                                "access",
                                List.of(
                                        Map.of(
                                                "users",
                                                List.of("alice"),
                                                "denyPermissions",
                                                Map.of("delete", true)
                                        )
                                )
                        )
                )
        );

        provider.start(reconciler);
        createDataAccess("default", "invalid-registry", invalidSpec);

        GenericKubernetesResource resource =
                awaitDataAccess("default", "invalid-registry", candidate ->
                        hasFinalizer(candidate)
                                && statusPhase(candidate).equals("Error"));

        assertTrue(reconciler.appliedStates.isEmpty());
        assertEquals(
                1L,
                statusNumber(resource, "observedGeneration")
        );
        assertTrue(
                !status(resource).containsKey("lastAppliedSpec")
        );
        provider.close();
    }

    @Test
    void preservesNestedBackendFailureDetailsInStatus()
            throws Exception
    {
        var reconciler = new RecordingReconciler();
        reconciler.failure = new StateReconciliationException(
                "reconciliation failed",
                new BackendOperationException("backend rejected policy")
        );
        var provider = new KubernetesStateProvider(kubernetesClient);
        provider.start(reconciler);
        createDataAccess(
                "default",
                "backend-failure",
                registrySpec("alice", "read")
        );

        GenericKubernetesResource failed = awaitDataAccess(
                "default",
                "backend-failure",
                resource -> statusPhase(resource).equals("Error")
        );
        String lastError = status(failed).get("lastError").toString();
        assertTrue(lastError.contains("StateReconciliationException"));
        assertTrue(lastError.contains("reconciliation failed"));
        assertTrue(lastError.contains("BackendOperationException"));
        assertTrue(lastError.contains("backend rejected policy"));
        assertTrue(reconciler.appliedStates.isEmpty());
        provider.close();
    }

    @Test
    void cleansUpOnDeleteEvent()
            throws Exception
    {
        var reconciler = new RecordingReconciler();
        var provider = new KubernetesStateProvider(kubernetesClient);
        provider.start(reconciler);
        createDataAccess(
                "default",
                "deleting",
                registrySpec("alice", "read")
        );
        awaitDataAccess("default", "deleting", candidate ->
                hasFinalizer(candidate)
                        && statusPhase(candidate).equals("Ready"));

        kubernetesClient.genericKubernetesResources(DATA_ACCESS)
                .inNamespace("default")
                .withName("deleting")
                .delete();

        awaitAppliedStateCount(reconciler, 2);
        assertEquals(
                PactState.empty(),
                reconciler.appliedStates.getLast()
        );
        provider.close();
        assertEquals(2, reconciler.appliedStates.size());
    }

    @Test
    void rejectsDataAccessWithoutSpec()
    {
        GenericKubernetesResource resource =
                new GenericKubernetesResource();

        resource.setApiVersion("com.example.ru/v1");
        resource.setKind("DataAccess");
        resource.setMetadata(
                new ObjectMetaBuilder()
                        .withName("invalid")
                        .withNamespace("default")
                        .build()
        );

        kubernetesClient
                .genericKubernetesResources(DATA_ACCESS)
                .inNamespace("default")
                .resource(resource)
                .create();

        KubernetesStateProvider provider =
                new KubernetesStateProvider(kubernetesClient);

        assertThrows(
                StateProviderException.class,
                provider::load
        );
    }

    @Test
    void wrapsInvalidDataAccessSpec()
    {
        createDataAccess(
                "default",
                "invalid",
                Map.of(
                        "resources",
                        "not-an-array"
                )
        );

        KubernetesStateProvider provider =
                new KubernetesStateProvider(kubernetesClient);

        assertThrows(
                StateProviderException.class,
                provider::load
        );
    }

    private void createDataAccess(
            String namespace,
            String name,
            Map<String, ?> spec)
    {
        kubernetesClient
                .genericKubernetesResources(DATA_ACCESS)
                .inNamespace(namespace)
                .resource(dataAccess(namespace, name, spec))
                .create();
    }

    private String createSecret(
            String namespace,
            String name,
            String key,
            String value)
    {
        Secret secret = new SecretBuilder()
                .withMetadata(new ObjectMetaBuilder()
                        .withName(name)
                        .withNamespace(namespace)
                        .build())
                .withData(Map.of(
                        key,
                        Base64.getEncoder().encodeToString(
                                value.getBytes(StandardCharsets.UTF_8)
                        )
                ))
                .build();
        kubernetesClient.secrets()
                .inNamespace(namespace)
                .resource(secret)
                .create();
        Secret created = kubernetesClient.secrets()
                .inNamespace(namespace)
                .withName(name)
                .get();
        return created.getMetadata().getResourceVersion();
    }

    private String replaceSecret(
            String namespace,
            String name,
            String key,
            String value)
    {
        Secret secret = kubernetesClient.secrets()
                .inNamespace(namespace)
                .withName(name)
                .get();
        secret.setData(Map.of(
                key,
                Base64.getEncoder().encodeToString(
                        value.getBytes(StandardCharsets.UTF_8)
                )
        ));
        kubernetesClient.secrets()
                .inNamespace(namespace)
                .resource(secret)
                .update();
        return kubernetesClient.secrets()
                .inNamespace(namespace)
                .withName(name)
                .get()
                .getMetadata()
                .getResourceVersion();
    }

    private GenericKubernetesResource dataAccess(
            String namespace,
            String name,
            Map<String, ?> spec)
    {
        GenericKubernetesResource resource =
                new GenericKubernetesResource();

        resource.setApiVersion("com.example.ru/v1");
        resource.setKind("DataAccess");
        resource.setMetadata(
                new ObjectMetaBuilder()
                        .withName(name)
                        .withNamespace(namespace)
                        .build()
        );
        resource.setAdditionalProperty("spec", spec);
        return resource;
    }

    @Test
    void deduplicatesAccessesFromDifferentDataAccessResources()
            throws StateProviderException
    {
        Map<String, ?> spec = Map.of(
                "resources",
                List.of(
                        Map.of(
                                "registry",
                                Map.of(
                                        "name",
                                        "repository-a"
                                ),
                                "access",
                                List.of(
                                        Map.of(
                                                "users",
                                                List.of("alice"),
                                                "permissions",
                                                List.of("read")
                                        )
                                )
                        )
                )
        );

        createDataAccess(
                "namespace-a",
                "access-a",
                spec
        );

        createDataAccess(
                "namespace-b",
                "access-b",
                spec
        );

        KubernetesStateProvider provider =
                new KubernetesStateProvider(kubernetesClient);

        var state = provider.load();

        assertEquals(1, state.accesses().size());
    }

    @Test
    void wrapsInvalidDataAccessResource()
    {
        createDataAccess(
                "default",
                "invalid",
                Map.of(
                        "resources",
                        List.of("not-an-object")
                )
        );

        KubernetesStateProvider provider =
                new KubernetesStateProvider(kubernetesClient);

        assertThrows(
                StateProviderException.class,
                provider::load
        );
    }

    private Map<String, ?> registrySpec(String user, String permission)
    {
        return Map.of(
                "resources",
                List.of(
                        Map.of(
                                "registry",
                                Map.of("name", "repository-a"),
                                "access",
                                List.of(
                                        Map.of(
                                                "users",
                                                List.of(user),
                                                "permissions",
                                                List.of(permission)
                                        )
                                )
                        )
                )
        );
    }

    private GenericKubernetesResource awaitDataAccess(
            String namespace,
            String name,
            java.util.function.Predicate<GenericKubernetesResource> condition)
            throws InterruptedException
    {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            GenericKubernetesResource resource =
                    kubernetesClient.genericKubernetesResources(DATA_ACCESS)
                            .inNamespace(namespace)
                            .withName(name)
                            .get();
            if (resource != null && condition.test(resource)) {
                return resource;
            }
            Thread.sleep(25L);
        }
        throw new AssertionError(
                "DataAccess did not reach the expected state: "
                        + namespace + "/" + name
        );
    }

    private void awaitAppliedStateCount(
            RecordingReconciler reconciler,
            int expectedMinimum)
            throws InterruptedException
    {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (reconciler.appliedStates.size() >= expectedMinimum) {
                return;
            }
            Thread.sleep(25L);
        }
        throw new AssertionError(
                "Expected at least "
                        + expectedMinimum
                        + " reconciliation event(s)"
        );
    }

    private boolean hasFinalizer(GenericKubernetesResource resource)
    {
        return resource.getMetadata().getFinalizers() != null
                && resource.getMetadata().getFinalizers()
                .contains("com.example.ru/data-access");
    }

    private String statusPhase(GenericKubernetesResource resource)
    {
        Object phase = status(resource).get("phase");
        return phase instanceof String string ? string : "";
    }

    private Map<?, ?> status(GenericKubernetesResource resource)
    {
        Object value = resource.getAdditionalProperties().get("status");
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private long statusNumber(
            GenericKubernetesResource resource,
            String field)
    {
        Object value = status(resource).get(field);
        return value instanceof Number number ? number.longValue() : -1L;
    }

    private String lastIdentitySecretVersion(
            GenericKubernetesResource resource)
    {
        Object value = status(resource).get("lastAppliedIdentityVersions");
        if (!(value instanceof List<?> versions) || versions.isEmpty()
                || !(versions.getLast() instanceof Map<?, ?> version)) {
            return "";
        }
        Object resourceVersion = version.get("resourceVersion");
        return resourceVersion instanceof String string ? string : "";
    }

    private static final class RecordingReconciler
            implements StateReconciler
    {
        private volatile PactState restoredState;
        private volatile StateReconciliationException failure;
        private final List<PactState> appliedStates =
                new CopyOnWriteArrayList<>();

        @Override
        public void restoreAppliedState(PactState state)
        {
            restoredState = state;
        }

        @Override
        public void apply(PactState desiredState)
                throws StateReconciliationException
        {
            if (failure != null) {
                throw failure;
            }
            appliedStates.add(desiredState);
        }
    }

}
