package io.github.pactproject.kubernetes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinitionBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.github.pactproject.api.PactState;
import io.github.pactproject.api.value.Value;
import io.github.pactproject.artifactkeeper.ArtifactKeeperApiProfile;
import io.github.pactproject.artifactkeeper.ArtifactKeeperBackend;
import io.github.pactproject.artifactkeeper.ArtifactKeeperConfig;
import io.github.pactproject.artifactkeeper.api.ArtifactKeeperApiPermission;
import io.github.pactproject.artifactkeeper.client.ArtifactKeeperHttpClient;
import io.github.pactproject.core.PactCore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Real Kubernetes API -> provider -> core -> real Artifact Keeper, with no backend mocks. */
@EnabledIfSystemProperty(named = "pact.integration", matches = "true")
@EnabledIfEnvironmentVariable(named = "PACT_IT_K8S_GROUP", matches = ".+")
class KubernetesArtifactKeeperLiveIntegrationTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temp;

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void recreatedRepositoryThenDeletedAndRecreatedDataAccessUsesNewUidsAndRevokesOldGrants(boolean finalizersEnabled) throws Exception {
        try (var fixture = new Fixture()) {
            var base = ArtifactKeeperControllerProcess.kubernetesConfig();
            var config = new io.github.pactproject.kubernetes.config.KubernetesConfig(
                    base.group(), base.version(), base.plural(), base.informerResyncMillis(),
                    base.informerStartTimeoutSeconds(), base.statusRetryDelaysMillis(), finalizersEnabled);
            var core = new PactCore(List.of(new ArtifactKeeperBackend("packages", fixture.ak)));
            try (var provider = new KubernetesStateProvider(new KubernetesClientBuilder().build(), config)) {
                provider.start(new ArtifactKeeperControllerProcess.CoreReconciler(core));
                fixture.createDataAccess(fixture.repository);
                fixture.awaitReady();
                fixture.assertGrants();
                var first = fixture.resources().withName(fixture.name).get();
                String oldUid = first.getMetadata().getUid();
                String oldRepositoryId = fixture.repositoryId, oldArtifactId = fixture.artifactId;
                var oldPermissionIds = fixture.permissions().stream().map(ArtifactKeeperApiPermission::id)
                        .collect(Collectors.toSet());
                assertEquals(finalizersEnabled, first.getMetadata().getFinalizers() != null
                        && first.getMetadata().getFinalizers().contains(base.group() + "/data-access"));
                fixture.recreateRepository();
                assertNotEquals(oldRepositoryId, fixture.repositoryId);
                fixture.resources().withName(fixture.name).delete();
                await(() -> fixture.resources().withName(fixture.name).get() == null, "deletion after repository recreation");
                if (finalizersEnabled) {
                    assertTrue(fixture.permissions().isEmpty(), "Enabled finalizer must finish cleanup before API deletion");
                }
                // With opt-out, recreate immediately after API deletion, without
                // waiting for asynchronous backend cleanup of the old UID.
                fixture.createDataAccess(fixture.repository);
                fixture.awaitReady();
                var replacement = fixture.resources().withName(fixture.name).get();
                assertNotEquals(oldUid, replacement.getMetadata().getUid());
                assertEquals(1L, replacement.getMetadata().getGeneration().longValue());
                fixture.assertGrants();
                assertFalse(fixture.permissions().stream().anyMatch(permission ->
                        permission.targetId().equals(oldRepositoryId)
                                || (!oldArtifactId.equals(fixture.artifactId) && permission.targetId().equals(oldArtifactId))));
                assertTrue(fixture.permissions().stream().noneMatch(permission -> oldPermissionIds.contains(permission.id())));
                fixture.deleteAndAwait();
            }
        }
    }

    @Test
    void startupCleansDeletingDataAccessAfterRepositoryRecreationAndAllowsSameNameAgain() throws Exception {
        try (var fixture = new Fixture()) {
            var config = ArtifactKeeperControllerProcess.kubernetesConfig();
            String oldUid;
            try (var first = new KubernetesStateProvider(new KubernetesClientBuilder().build(), config)) {
                var core = new PactCore(List.of(new ArtifactKeeperBackend("packages", fixture.ak)));
                first.start(new ArtifactKeeperControllerProcess.CoreReconciler(core));
                fixture.createDataAccess(fixture.repository);
                fixture.awaitReady();
                oldUid = fixture.resources().withName(fixture.name).get().getMetadata().getUid();
            }
            String oldRepositoryId = fixture.repositoryId;
            fixture.recreateRepository();
            assertNotEquals(oldRepositoryId, fixture.repositoryId);
            fixture.resources().withName(fixture.name).delete();
            var deleting = fixture.resources().withName(fixture.name).get();
            assertNotNull(deleting);
            assertNotNull(deleting.getMetadata().getDeletionTimestamp());
            assertNotNull(deleting.getAdditionalProperties().get("status"));
            var restartedCore = new PactCore(List.of(new ArtifactKeeperBackend("packages", fixture.ak)));
            try (var restarted = new KubernetesStateProvider(new KubernetesClientBuilder().build(), config)) {
                restarted.start(new ArtifactKeeperControllerProcess.CoreReconciler(restartedCore));
                await(() -> fixture.resources().withName(fixture.name).get() == null, "startup cleanup of deleting DataAccess");
                assertTrue(fixture.permissions().isEmpty());
                fixture.createDataAccess(fixture.repository);
                fixture.awaitReady();
                assertNotEquals(oldUid, fixture.resources().withName(fixture.name).get().getMetadata().getUid());
                fixture.assertGrants();
                fixture.deleteAndAwait();
            }
        }
    }

    @Test
    void overlappingDataAccessKeepsEqualAggregateAndRequiresStartupToRepairRecreatedRepository() throws Exception {
        try (var fixture = new Fixture()) {
            String sharedName = fixture.name + "-shared";
            var config = ArtifactKeeperControllerProcess.kubernetesConfig();
            var core = new PactCore(List.of(new ArtifactKeeperBackend("packages", fixture.ak)));
            try (var first = new KubernetesStateProvider(new KubernetesClientBuilder().build(), config)) {
                first.start(new ArtifactKeeperControllerProcess.CoreReconciler(core));
                fixture.createDataAccess(fixture.repository);
                fixture.awaitReady();
                fixture.createDataAccess(fixture.repository, sharedName);
                fixture.awaitReady(sharedName);
                fixture.recreateRepository();
                fixture.resources().withName(fixture.name).delete();
                await(() -> fixture.resources().withName(fixture.name).get() == null, "delete while shared access remains");
                fixture.createDataAccess(fixture.repository);
                fixture.awaitReady();
                assertTrue(fixture.permissions().stream().noneMatch(permission -> permission.targetId().equals(fixture.repositoryId)),
                        "Equal aggregate is deliberately skipped; replacing one overlapping DataAccess is not a drift-repair trigger");
            }
            var restartedCore = new PactCore(List.of(new ArtifactKeeperBackend("packages", fixture.ak)));
            try (var restarted = new KubernetesStateProvider(new KubernetesClientBuilder().build(), config)) {
                restarted.start(new ArtifactKeeperControllerProcess.CoreReconciler(restartedCore));
                fixture.awaitReady();
                fixture.awaitReady(sharedName);
                fixture.assertGrants();
                fixture.resources().withName(fixture.name).delete();
                await(() -> fixture.resources().withName(fixture.name).get() == null, "remove first overlapping declaration");
                fixture.assertGrants();
                fixture.deleteAndAwait(sharedName);
            }
        }
    }

    @Test
    void optOutReleasesDeletingResourceBeforeFailedStartupCleanupOnRealApi() throws Exception {
        try (var fixture = new Fixture()) {
            var config = ArtifactKeeperControllerProcess.kubernetesConfig();
            var core = new PactCore(List.of(new ArtifactKeeperBackend("packages", fixture.ak)));
            try (var provider = new KubernetesStateProvider(new KubernetesClientBuilder().build(), config)) {
                provider.start(new ArtifactKeeperControllerProcess.CoreReconciler(core));
                fixture.createDataAccess();
                fixture.awaitReady();
            }
            var grants = fixture.permissions();
            assertFalse(grants.isEmpty());
            fixture.resources().withName(fixture.name).delete();
            var deleting = fixture.resources().withName(fixture.name).get();
            assertNotNull(deleting);
            assertNotNull(deleting.getMetadata().getDeletionTimestamp());
            assertTrue(deleting.getMetadata().getFinalizers().contains(config.group() + "/data-access"));
            var disabled = new io.github.pactproject.kubernetes.config.KubernetesConfig(
                    config.group(), config.version(), config.plural(), config.informerResyncMillis(),
                    config.informerStartTimeoutSeconds(), config.statusRetryDelaysMillis(), false);
            try (var provider = new KubernetesStateProvider(new KubernetesClientBuilder().build(), disabled)) {
                assertThrows(io.github.pactproject.api.exception.StateProviderException.class,
                        () -> provider.start(new io.github.pactproject.api.StateReconciler() {
                            @Override public void restoreAppliedState(PactState state) {
                                assertFalse(state.accesses().isEmpty(), "Persisted snapshot must be read before it is lost");
                            }
                            @Override public void apply(PactState state)
                                    throws io.github.pactproject.api.exception.StateReconciliationException {
                                assertTrue(state.accesses().isEmpty());
                                throw new io.github.pactproject.api.exception.StateReconciliationException(
                                        "Synthetic cleanup outage", new java.io.IOException("Fixture backend unavailable"));
                            }
                        }));
                await(() -> fixture.resources().withName(fixture.name).get() == null, "opt-out releases real API deletion");
                assertEquals(grants, fixture.permissions(), "Opt-out cannot guarantee cleanup after backend failure");
            }
        }
    }

    @Test
    void reconcilesNamedYamlMixedPrincipalsAndFlatCidrsThroughRealKubernetes() throws Exception {
        try (var fixture = new Fixture()) {
            var backend = new ArtifactKeeperBackend("packages", fixture.ak, ArtifactKeeperApiProfile.CURRENT);
            var core = new PactCore(List.of(backend));
            try (var provider = new KubernetesStateProvider(new KubernetesClientBuilder().build(),
                    ArtifactKeeperControllerProcess.kubernetesConfig())) {
                provider.start(new ArtifactKeeperControllerProcess.CoreReconciler(core));
                fixture.createDataAccess();
                fixture.awaitReady();
                fixture.assertGrants();
                // A real API update exercises the same flattened YAML through watch and compilation again.
                var resource = fixture.resources().withName(fixture.name).get();
                @SuppressWarnings("unchecked")
                var spec = (Map<String, Object>) resource.getAdditionalProperties().get("spec");
                @SuppressWarnings("unchecked")
                var resources = (List<Map<String, Object>>) spec.get("resources");
                @SuppressWarnings("unchecked")
                var access = (List<Map<String, Object>>) resources.getFirst().get("access");
                access.getFirst().put("permissions", List.of("read", "write"));
                fixture.resources().resource(resource).update();
                fixture.awaitReady();
                await(() -> fixture.permissions().stream().filter(p -> p.targetType().equals("repository"))
                        .allMatch(p -> p.actions().equals(Set.of("read", "write"))), "updated repository grants");
                fixture.deleteAndAwait();
            }
        }
    }

    @Test
    void restoresNamedStateInANewJvmWithoutRewritingGrantsAndRevokesOnDeletion() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.createDataAccess();
            try (var first = startController("first")) {
                await(() -> Files.exists(first.marker), "first process startup");
                assertEquals("0", Files.readString(first.marker));
                fixture.awaitReady();
                fixture.assertGrants();
            }
            List<ArtifactKeeperApiPermission> beforeRestart = fixture.permissions();
            try (var restarted = startController("restarted")) {
                await(() -> Files.exists(restarted.marker), "second process restoration");
                assertEquals("5", Files.readString(restarted.marker), "New JVM must restore the complete lastAppliedSpec");
                assertEquals(Set.copyOf(beforeRestart), Set.copyOf(fixture.permissions()),
                        "Restart with the same names must preserve physical permission IDs and restrictions");
                fixture.deleteAndAwait();
            }
        }
    }

    private ControllerProcess startController(String phase) throws Exception {
        Path marker = temp.resolve(phase + ".ready");
        Path log = temp.resolve(phase + ".log");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var process = new ProcessBuilder(java, "--enable-preview", "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                ArtifactKeeperControllerProcess.class.getName(), marker.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        return new ControllerProcess(process, marker);
    }

    private record ControllerProcess(Process process, Path marker) implements AutoCloseable {
        @Override public void close() throws Exception {
            if (process.isAlive()) {
                process.getOutputStream().write('\n');
                process.getOutputStream().flush();
            }
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new AssertionError("Controller JVM did not stop gracefully");
            }
            assertEquals(0, process.exitValue(), "Controller JVM must complete without errors");
        }
    }

    private static void await(BooleanSupplier condition, String description) throws Exception {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(100);
        }
        fail("Timed out waiting for " + description);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing test variable: " + name);
        return value;
    }

    private static final class Fixture implements AutoCloseable {
        final KubernetesClient kubernetes = new KubernetesClientBuilder().build();
        final String namespace = required("PACT_IT_K8S_NAMESPACE");
        final String group = required("PACT_IT_K8S_GROUP");
        final String suffix = UUID.randomUUID().toString();
        final String name = "pact-it-" + suffix;
        final String repository = "pact-it-k8s-" + suffix;
        final String displayName = "PACT Kubernetes repository " + suffix;
        final String projectName = "PACT Kubernetes project " + suffix;
        final String groupName = "pact-it-group-" + suffix;
        final String serviceAccount = "svc-pact-it-k8s-" + suffix;
        final String user = required("PACT_IT_ARTIFACT_KEEPER_USER");
        final String url = required("PACT_IT_ARTIFACT_KEEPER_URL").replaceAll("/+$", "");
        final String token = required("PACT_IT_ARTIFACT_KEEPER_TOKEN");
        final ArtifactKeeperHttpClient ak = new ArtifactKeeperHttpClient(new ArtifactKeeperConfig(
                URI.create(url), token, 100, ArtifactKeeperApiProfile.CURRENT));
        final ResourceDefinitionContext context = new ResourceDefinitionContext.Builder()
                .withGroup(group).withVersion(System.getenv().getOrDefault("PACT_IT_K8S_VERSION", "v1"))
                .withPlural(System.getenv().getOrDefault("PACT_IT_K8S_PLURAL", "dataaccesses"))
                .withKind("DataAccess").withNamespaced(true).build();
        final ArrayDeque<String> cleanup = new ArrayDeque<>();
        final Set<String> dataAccessNames = new java.util.LinkedHashSet<>();
        String repositoryId;
        String projectId;
        String groupId;
        String artifactId;
        boolean createdCrd;
        boolean createdNamespace;

        Fixture() throws Exception {
            assertTrue(permissions().isEmpty(), "Use a disposable Artifact Keeper without existing permissions");
            // Creating a dedicated CRD is opt-in through the integration-only API group.
            assertEquals("integration.pactproject.github.io", group, "This E2E owns only its dedicated integration CRD");
            String crdName = context.getPlural() + "." + group;
            if (kubernetes.apiextensions().v1().customResourceDefinitions().withName(crdName).get() == null) {
                kubernetes.apiextensions().v1().customResourceDefinitions().resource(new CustomResourceDefinitionBuilder()
                        .withNewMetadata().withName(crdName).endMetadata()
                        .withNewSpec().withGroup(group).withScope("Namespaced")
                        .withNewNames().withPlural(context.getPlural()).withSingular("dataaccess").withKind("DataAccess").endNames()
                        .addNewVersion().withName(context.getVersion()).withServed(true).withStorage(true)
                        .withNewSchema().withNewOpenAPIV3Schema().withType("object")
                        .addToProperties("spec", new io.fabric8.kubernetes.api.model.apiextensions.v1.JSONSchemaPropsBuilder()
                                .withType("object").withXKubernetesPreserveUnknownFields(true).build())
                        .addToProperties("status", new io.fabric8.kubernetes.api.model.apiextensions.v1.JSONSchemaPropsBuilder()
                                .withType("object").withXKubernetesPreserveUnknownFields(true).build())
                        .endOpenAPIV3Schema().endSchema().withNewSubresources().withStatus(
                                new io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceSubresourceStatus()).endSubresources()
                        .endVersion().endSpec().build()).create();
                createdCrd = true;
                await(() -> {
                    var crd = kubernetes.apiextensions().v1().customResourceDefinitions().withName(crdName).get();
                    return crd != null && crd.getStatus() != null && crd.getStatus().getConditions() != null
                            && crd.getStatus().getConditions().stream().anyMatch(c -> c.getType().equals("Established") && c.getStatus().equals("True"));
                }, "CRD establishment");
            }
            assertTrue(kubernetes.genericKubernetesResources(context).inAnyNamespace().list().getItems().isEmpty(),
                    "Use a dedicated integration CRD without preexisting objects");
            if (kubernetes.namespaces().withName(namespace).get() == null) {
                kubernetes.namespaces().resource(new io.fabric8.kubernetes.api.model.NamespaceBuilder()
                        .withNewMetadata().withName(namespace).endMetadata().build()).create();
                createdNamespace = true;
            }
            projectId = api("POST", "/api/v1/projects", Map.of("key", "pact-it-" + suffix, "name", projectName)).path("id").asText();
            cleanup.push("/api/v1/projects/" + projectId);
            groupId = api("POST", "/api/v1/groups", Map.of("name", groupName)).path("id").asText();
            cleanup.push("/api/v1/groups/" + groupId);
            repositoryId = api("POST", "/api/v1/repositories", Map.of("key", repository, "name", displayName,
                    "format", "generic", "repo_type", "local", "is_public", false)).path("id").asText();
            cleanup.push("/api/v1/repositories/" + repository);
            artifactId = api("PUT", "/api/v1/repositories/" + repository + "/artifacts/org/app.bin", Map.of("fixture", "kubernetes"))
                    .path("id").asText();
        }

        io.fabric8.kubernetes.client.dsl.NonNamespaceOperation<GenericKubernetesResource,
                io.fabric8.kubernetes.api.model.GenericKubernetesResourceList,
                io.fabric8.kubernetes.client.dsl.Resource<GenericKubernetesResource>> resources() {
            return kubernetes.genericKubernetesResources(context).inNamespace(namespace);
        }

        void createDataAccess() {
            createDataAccess(displayName);
        }

        void recreateRepository() throws Exception {
            api("DELETE", "/api/v1/repositories/" + repository, null);
            repositoryId = api("POST", "/api/v1/repositories", Map.of(
                    "key", repository, "name", displayName + " replacement", "format", "generic",
                    "repo_type", "local", "is_public", false)).path("id").asText();
            artifactId = api("PUT", "/api/v1/repositories/" + repository + "/artifacts/org/app.bin",
                    Map.of("fixture", "kubernetes")).path("id").asText();
        }

        void createDataAccess(String repositorySelector) {
            createDataAccess(repositorySelector, name);
        }

        void createDataAccess(String repositorySelector, String resourceName) {
            // Parse the public YAML shape, rather than constructing an already compiled PactState.
            String yaml = """
                    apiVersion: %s/%s
                    kind: DataAccess
                    metadata:
                      name: %s
                      namespace: %s
                    spec:
                      resources:
                        - packages:
                            name: '%s'
                          access:
                            - users: [%s, %s]
                              permissions: [read]
                              allowed_cidrs: [10.0.0.0/8]
                        - packages:
                            type: artifact
                            repository: '%s'
                            path: org/app.bin
                          access:
                            - users: [%s]
                              permissions: [read]
                        - packages:
                            type: project
                            name: '%s'
                          access:
                            - users: [%s]
                              permissions: [read]
                        - packages:
                            type: group
                            name: %s
                          access:
                            - users: [%s]
                              principal-type: group
                              permissions: [read]
                    """.formatted(group, context.getVersion(), resourceName, namespace, repositorySelector, user, serviceAccount,
                    repositorySelector, serviceAccount, projectName, user, groupName, groupName);
            dataAccessNames.add(resourceName);
            resources().resource(Serialization.unmarshal(yaml, GenericKubernetesResource.class)).create();
        }

        void awaitReady() throws Exception {
            awaitReady(name);
        }

        @SuppressWarnings("unchecked")
        void awaitReady(String resourceName) throws Exception {
            await(() -> {
                var resource = resources().withName(resourceName).get();
                if (resource == null || !(resource.getAdditionalProperties().get("status") instanceof Map<?, ?> status)) return false;
                if ("Error".equals(status.get("phase"))) fail("DataAccess failed: " + status.get("lastError"));
                return "Ready".equals(status.get("phase"))
                        && ((Number) status.get("observedGeneration")).longValue() == resource.getMetadata().getGeneration();
            }, "Ready DataAccess with its current generation");
            var status = (Map<String, Object>) resources().withName(resourceName).get().getAdditionalProperties().get("status");
            assertNotNull(status.get("lastAppliedSpec"));
            assertTrue(status.get("lastAppliedSpec").toString().contains("allowed_cidrs"));
            assertFalse(status.get("lastAppliedSpec").toString().contains("conditions"));
        }

        List<ArtifactKeeperApiPermission> permissions() {
            try { return ak.getPermissions(); }
            catch (io.github.pactproject.artifactkeeper.client.ArtifactKeeperClientException e) { throw new AssertionError("Cannot inspect live grants", e); }
        }

        void assertGrants() throws Exception {
            var grants = permissions();
            assertEquals(5, grants.size());
            List<io.github.pactproject.artifactkeeper.api.ArtifactKeeperApiUser> users;
            try { users = ak.getUsers(); }
            catch (io.github.pactproject.artifactkeeper.client.ArtifactKeeperClientException e) {
                throw new AssertionError("Cannot inspect live users", e);
            }
            String humanId = users.stream().filter(u -> u.username().equals(user)).findFirst().orElseThrow().id();
            String serviceId = users.stream().filter(u -> u.username().equals(serviceAccount)).findFirst().orElseThrow().id();
            assertEquals(Set.of("repository:" + repositoryId, "artifact:" + artifactId, "project:" + projectId, "group:" + groupId),
                    grants.stream().map(p -> p.targetType() + ":" + p.targetId()).collect(Collectors.toSet()));
            for (var grant : grants) {
                assertEquals(Set.of("read"), grant.actions());
                switch (grant.targetType()) {
                    case "repository" -> {
                        assertTrue(Set.of(humanId, serviceId).contains(grant.principalId()));
                        assertEquals(grant.principalId().equals(humanId) ? "user" : "service_account", grant.principalType());
                        assertEquals(Map.of("allowed_cidrs", Value.set(Set.of(Value.string("10.0.0.0/8")))), grant.conditions());
                    }
                    case "artifact" -> { assertEquals(serviceId, grant.principalId()); assertEquals("service_account", grant.principalType()); }
                    case "project" -> { assertEquals(humanId, grant.principalId()); assertEquals("user", grant.principalType()); }
                    case "group" -> { assertEquals(groupId, grant.principalId()); assertEquals("group", grant.principalType()); }
                    default -> fail("Unexpected target type");
                }
            }
        }

        void deleteAndAwait() throws Exception {
            deleteAndAwait(name);
        }

        void deleteAndAwait(String resourceName) throws Exception {
            resources().withName(resourceName).delete();
            await(() -> resources().withName(resourceName).get() == null, "DataAccess finalizer cleanup");
            var inconsistent = new java.util.concurrent.atomic.AtomicReference<Exception>();
            try {
                await(() -> {
                    try {
                        boolean empty = ak.getPermissions().isEmpty();
                        inconsistent.set(null);
                        return empty;
                    } catch (io.github.pactproject.artifactkeeper.client.ArtifactKeeperClientException failure) {
                        // An observer can race asynchronous opt-out cleanup.
                        // Keep waiting for a successful complete read; an
                        // inconsistent catalog is never evidence of emptiness.
                        if (failure.getMessage() == null || !failure.getMessage().startsWith(
                                "Artifact Keeper catalog is incomplete or inconsistent: /api/v1/permissions")) {
                            throw new AssertionError("Cannot inspect live grants", failure);
                        }
                        inconsistent.set(failure);
                        return false;
                    }
                }, "removal of actual Artifact Keeper permissions");
            } catch (AssertionError timeout) {
                if (inconsistent.get() != null) timeout.addSuppressed(inconsistent.get());
                throw timeout;
            }
        }

        JsonNode api(String method, String path, Object body) throws Exception {
            var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url + path))
                    .timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertTrue(response.statusCode() >= 200 && response.statusCode() < 300, method + " " + path + " returned " + response.statusCode());
            return response.body().isBlank() ? JSON.nullNode() : JSON.readTree(response.body());
        }

        @Override public void close() throws Exception {
            try {
                new ArtifactKeeperBackend("packages", ak, ArtifactKeeperApiProfile.CURRENT).apply(PactState.empty());
                for (String resourceName : dataAccessNames) {
                    var resource = resources().withName(resourceName).get();
                    if (resource != null) {
                        resource.getMetadata().setFinalizers(List.of());
                        resources().resource(resource).update();
                        resources().withName(resourceName).delete();
                    }
                }
                while (!cleanup.isEmpty()) api("DELETE", cleanup.pop(), null);
            } finally {
                try {
                    if (createdCrd) {
                        String crd = context.getPlural() + "." + group;
                        kubernetes.apiextensions().v1().customResourceDefinitions().withName(crd).delete();
                        await(() -> kubernetes.apiextensions().v1().customResourceDefinitions().withName(crd).get() == null, "CRD deletion");
                    }
                    if (createdNamespace) {
                        kubernetes.namespaces().withName(namespace).delete();
                        await(() -> kubernetes.namespaces().withName(namespace).get() == null, "namespace deletion");
                    }
                } finally { kubernetes.close(); }
            }
        }
    }
}
