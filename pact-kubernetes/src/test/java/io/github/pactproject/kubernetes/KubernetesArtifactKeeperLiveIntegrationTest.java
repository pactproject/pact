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
                    """.formatted(group, context.getVersion(), name, namespace, displayName, user, serviceAccount,
                    displayName, serviceAccount, projectName, user, groupName, groupName);
            resources().resource(Serialization.unmarshal(yaml, GenericKubernetesResource.class)).create();
        }

        @SuppressWarnings("unchecked")
        void awaitReady() throws Exception {
            await(() -> {
                var resource = resources().withName(name).get();
                if (resource == null || !(resource.getAdditionalProperties().get("status") instanceof Map<?, ?> status)) return false;
                if ("Error".equals(status.get("phase"))) fail("DataAccess failed: " + status.get("lastError"));
                return "Ready".equals(status.get("phase"))
                        && ((Number) status.get("observedGeneration")).longValue() == resource.getMetadata().getGeneration();
            }, "Ready DataAccess with its current generation");
            var status = (Map<String, Object>) resources().withName(name).get().getAdditionalProperties().get("status");
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
            resources().withName(name).delete();
            await(() -> resources().withName(name).get() == null, "DataAccess finalizer cleanup");
            await(() -> permissions().isEmpty(), "removal of actual Artifact Keeper permissions");
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
                var resource = resources().withName(name).get();
                if (resource != null) {
                    resource.getMetadata().setFinalizers(List.of());
                    resources().resource(resource).update();
                    resources().withName(name).delete();
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
