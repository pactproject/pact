package io.github.pactproject.artifactkeeper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pactproject.api.Access;
import io.github.pactproject.api.BackendTransaction;
import io.github.pactproject.api.PactState;
import io.github.pactproject.api.Resource;
import io.github.pactproject.api.value.Value;
import io.github.pactproject.artifactkeeper.api.ArtifactKeeperApiPermission;
import io.github.pactproject.artifactkeeper.client.ArtifactKeeperClientException;
import io.github.pactproject.artifactkeeper.client.ArtifactKeeperHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "pact.integration", matches = "true")
class ArtifactKeeperLiveIntegrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {"repository", "artifact", "project", "group", "system", "global", "future_scope"})
    void reconcilesEveryTargetTypeThroughLivePermissionApi(String type)
            throws Exception, ArtifactKeeperClientException {
        var config = config(100);
        var client = new ArtifactKeeperHttpClient(config);
        assertTrue(client.getPermissions().isEmpty(), "Use a dedicated instance");
        String targetId = type.equals("repository")
                ? repositoryId(client, required("PACT_IT_ARTIFACT_KEEPER_REPOSITORY"))
                : type.equals("system") ? "00000000-0000-0000-0000-000000000000" : UUID.randomUUID().toString();
        Map<String, Value> conditions = config.apiProfile() == ArtifactKeeperApiProfile.CURRENT
                ? Map.of("allowed_cidrs", Value.set(Set.of(
                        Value.string("10.0.0.0/8"), Value.string("2001:db8::/32"))))
                : Map.of();
        var backend = new ArtifactKeeperBackend("registry", client, config.apiProfile());
        var desired = typedState(required("PACT_IT_ARTIFACT_KEEPER_USER"), "user", type, targetId,
                Set.of("read"), conditions);
        try {
            backend.apply(desired);
            var grant = client.getPermissions().getFirst();
            assertEquals(type, grant.targetType());
            assertEquals(targetId, grant.targetId());
            assertEquals(conditions, grant.conditions());
            backend.apply(desired);
            assertEquals(List.of(grant), client.getPermissions(), "Identical state must preserve the grant ID");
            backend.apply(typedState(required("PACT_IT_ARTIFACT_KEEPER_USER"), "user", type, targetId,
                    Set.of("read", "write"), conditions));
            var updated = client.getPermissions().getFirst();
            assertEquals(type, updated.targetType());
            assertEquals(targetId, updated.targetId());
            assertEquals(Set.of("read", "write"), updated.actions());
            assertEquals(conditions, updated.conditions());
        } finally {
            backend.apply(PactState.empty());
        }
        assertTrue(client.getPermissions().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"group", "anonymous"})
    @EnabledIfEnvironmentVariable(named = "PACT_IT_ARTIFACT_KEEPER_API_PROFILE", matches = "current")
    void reconcilesNonUserPrincipalsThroughLiveApi(String principalType)
            throws Exception, ArtifactKeeperClientException {
        var config = config(100);
        var client = new ArtifactKeeperHttpClient(config);
        assertTrue(client.getPermissions().isEmpty(), "Use a dedicated instance");
        String groupId = null;
        String principal = "anonymous";
        if (principalType.equals("group")) {
            groupId = apiJson(config, "POST", "/api/v1/groups", MAPPER.valueToTree(Map.of(
                    "name", "pact-it-" + UUID.randomUUID(), "description", "PACT permission test"))).path("id").asText();
            principal = groupId;
        }
        var backend = new ArtifactKeeperBackend("registry", client, config.apiProfile());
        var desired = typedState(principal, principalType, "repository",
                repositoryId(client, required("PACT_IT_ARTIFACT_KEEPER_REPOSITORY")), Set.of("read"), Map.of());
        try {
            backend.apply(desired);
            var grant = client.getPermissions().getFirst();
            assertEquals(principalType, grant.principalType());
            assertEquals(groupId == null ? "00000000-0000-0000-0000-000000000000" : groupId, grant.principalId());
            backend.apply(desired);
            assertEquals(List.of(grant), client.getPermissions());
        } finally {
            try {
                backend.apply(PactState.empty());
            } finally {
                if (groupId != null) apiJson(config, "DELETE", "/api/v1/groups/" + groupId, null);
            }
        }
        assertTrue(client.getPermissions().isEmpty());
    }

    private static PactState typedState(String principal, String principalType, String type, String id,
                                         Set<String> actions, Map<String, Value> conditions) {
        return new PactState(Set.of(new Access(principal, new Resource("registry", Map.of("type", type, "id", id)),
                Map.of("principal-type", Value.string(principalType), "conditions", Value.object(conditions),
                        "actions", Value.set(actions.stream().map(Value::string).collect(Collectors.toSet()))))));
    }

    @Test
    void preservesCustomBackendNameAndMixedHumanAndServiceAccountAccess()
            throws Exception, ArtifactKeeperClientException {
        var config = config(100);
        var client = new ArtifactKeeperHttpClient(config);
        assertTrue(client.getPermissions().isEmpty(), "Use a dedicated instance");
        String human = required("PACT_IT_ARTIFACT_KEEPER_USER");
        String serviceAccount = "svc-pact-it-compat-" + UUID.randomUUID().toString().replace("-", "");
        String repository = required("PACT_IT_ARTIFACT_KEEPER_REPOSITORY");
        var desired = new PactState(Set.of(human, serviceAccount).stream().map(principal -> new Access(
                principal, new Resource("company-registry", Map.of("name", repository)),
                Map.of("permissions", Value.set(Set.of(Value.string("read"))))
        )).collect(Collectors.toSet()));
        var backend = new ArtifactKeeperBackend("company-registry", client, config.apiProfile());
        try {
            backend.apply(desired);
            var grants = client.getPermissions();
            assertEquals(2, grants.size());
            String repositoryId = repositoryId(client, repository);
            assertTrue(grants.stream().allMatch(p -> p.targetType().equals("repository")
                    && p.targetId().equals(repositoryId)));
            var users = client.getUsers();
            for (var name : List.of(human, serviceAccount)) {
                var account = users.stream().filter(u -> u.username().equals(name)).findFirst().orElseThrow();
                assertEquals(name.equals(serviceAccount), account.serviceAccount());
                assertEquals(name.equals(serviceAccount) ? config.apiProfile().permissionPrincipalType("service_account") : "user",
                        grants.stream().filter(p -> p.principalId().equals(account.id())).findFirst().orElseThrow().principalType());
            }
            backend.apply(desired);
            assertEquals(grants, client.getPermissions());
        } finally {
            backend.apply(PactState.empty());
        }
        assertTrue(client.getPermissions().isEmpty());
    }

    @Test
    void reconcilesRepositoryPermissionsAgainstLiveApi()
            throws Exception, ArtifactKeeperClientException {
        ArtifactKeeperConfig config = config(100);
        ArtifactKeeperHttpClient client = new ArtifactKeeperHttpClient(config);
        String username = required("PACT_IT_ARTIFACT_KEEPER_USER");
        String repositoryName = required("PACT_IT_ARTIFACT_KEEPER_REPOSITORY");

        assertTrue(
                client.getPermissions().isEmpty(),
                "Use a dedicated Artifact Keeper instance without unrelated permissions"
        );
        String userId = client.getUsers().stream()
                .filter(user -> user.username().equals(username))
                .map(user -> user.id())
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Configured integration-test user must already exist"
                ));
        String repositoryId = client.getRepositories().stream()
                .filter(repository -> repository.name().equals(repositoryName))
                .map(repository -> repository.id())
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Configured integration-test repository must already exist"
                ));

        ArtifactKeeperBackend backend = new ArtifactKeeperBackend("registry", client, config.apiProfile());
        PactState initial = state(username, repositoryName, Set.of("read"));
        PactState updated = state(username, repositoryName, Set.of("read", "write"));
        BackendTransaction create = backend.prepare(PactState.empty(), initial);
        BackendTransaction update = null;

        try {
            create.apply();
            assertPermission(
                    client.getPermissions(),
                    userId,
                    repositoryId,
                    "user",
                    Set.of("read")
            );

            update = backend.prepare(initial, updated);
            update.apply();
            assertPermission(
                    client.getPermissions(),
                    userId,
                    repositoryId,
                    "user",
                    Set.of("read", "write")
            );

            backend.apply(updated);
            assertPermission(
                    client.getPermissions(),
                    userId,
                    repositoryId,
                    "user",
                    Set.of("read", "write")
            );

            backend.apply(PactState.empty());
            assertTrue(client.getPermissions().isEmpty());
        }
        finally {
            if (update != null) {
                update.rollback();
            }
            create.rollback();
        }

        assertTrue(
                client.getPermissions().isEmpty(),
                "Integration test must restore the dedicated Artifact Keeper instance"
        );
    }

    @Test
    void createsServiceAccountWhenEnsuringPermission()
            throws Exception, ArtifactKeeperClientException {
        ArtifactKeeperConfig config = config(100);
        ArtifactKeeperHttpClient client = new ArtifactKeeperHttpClient(config);
        String repositoryName = "pact-it-private-" + UUID.randomUUID().toString().replace("-", "");
        String username = "svc-pact-it-" + UUID.randomUUID()
                .toString().replace("-", "");

        assertTrue(
                client.getPermissions().isEmpty(),
                "Use a dedicated Artifact Keeper instance without unrelated permissions"
        );
        assertTrue(
                client.getUsers().stream()
                        .noneMatch(user -> user.username().equals(username)),
                "The generated service account name must be unused"
        );
        String repositoryId = apiJson(config, "POST", "/api/v1/repositories", MAPPER.valueToTree(Map.of(
                "key", repositoryName, "name", repositoryName,
                "format", "maven", "repo_type", "local", "is_public", false
        ))).path("id").asText();
        ArtifactKeeperBackend backend = new ArtifactKeeperBackend("registry", client, config.apiProfile());
        PactState desired = state(username, repositoryName, Set.of("read"));
        BackendTransaction transaction =
                backend.prepare(PactState.empty(), desired);
        String tokenPath = null;

        try {
            transaction.apply();
            String userId = client.getUsers().stream()
                    .filter(user -> user.username().equals(username))
                    .map(user -> user.id())
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "Expected PACT to create the service account"
                    ));
            assertPermission(
                    client.getPermissions(),
                    userId,
                    repositoryId,
                    config.apiProfile().permissionPrincipalType("service_account"),
                    Set.of("read")
            );
            String permissionId = client.getPermissions().getFirst().id();
            backend.apply(desired);
            assertEquals(permissionId, client.getPermissions().getFirst().id(),
                    "Repeated reconciliation must preserve the permission ID");

            String accountTokensPath = "/api/v1/service-accounts/" + userId + "/tokens";
            JsonNode token = apiJson(config, "POST", accountTokensPath, MAPPER.valueToTree(Map.of(
                    "name", "pact-it-read", "scopes", List.of("read:artifacts"), "expires_in_days", 1
            )));
            tokenPath = accountTokensPath + "/" + token.path("id").asText();
            String accountToken = token.path("token").asText();
            assertEquals(200, apiRequest(config, accountToken, "GET",
                    "/api/v1/repositories/" + repositoryName, null).statusCode(),
                    "Service-account grant must allow reading a private repository");

            transaction.rollback();
            int denied = apiRequest(config, accountToken, "GET",
                    "/api/v1/repositories/" + repositoryName, null).statusCode();
            assertTrue(denied == 403 || denied == 404,
                    "Revoking the grant must deny repository access; got " + denied);
        }
        finally {
            try {
                transaction.rollback();
            }
            finally {
                try {
                    if (tokenPath != null) {
                        apiJson(config, "DELETE", tokenPath, null);
                    }
                }
                finally {
                    apiJson(config, "DELETE", "/api/v1/repositories/" + repositoryName, null);
                }
            }
        }

        assertTrue(
                client.getPermissions().isEmpty(),
                "Service-account test must remove its permission"
        );
        assertTrue(
                client.getUsers().stream()
                        .anyMatch(user -> user.username().equals(username)),
                "Created service accounts are retained after permission cleanup"
        );
    }

    @Test
    void loadsAndReconcilesPermissionsAcrossMultiplePages()
            throws Exception, ArtifactKeeperClientException {
        ArtifactKeeperConfig config = config(1);
        ArtifactKeeperHttpClient client = new ArtifactKeeperHttpClient(config);
        String username = required("PACT_IT_ARTIFACT_KEEPER_USER");
        List<String> repositories = client.getRepositories().stream()
                .map(repository -> repository.name())
                .distinct()
                .limit(2)
                .toList();

        assertEquals(
                2,
                repositories.size(),
                "Pagination test needs two existing repositories"
        );
        assertTrue(
                client.getPermissions().isEmpty(),
                "Use a dedicated Artifact Keeper instance without unrelated permissions"
        );
        String userId = client.getUsers().stream()
                .filter(user -> user.username().equals(username))
                .map(user -> user.id())
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Configured integration-test user must already exist"
                ));
        Map<String, String> repositoryIds = client.getRepositories().stream()
                .filter(repository -> repositories.contains(repository.name()))
                .collect(Collectors.toMap(
                        repository -> repository.name(),
                        repository -> repository.id()
                ));

        ArtifactKeeperBackend backend = new ArtifactKeeperBackend("registry", client, config.apiProfile());
        PactState desired = new PactState(repositories.stream()
                .map(repository -> new Access(
                        username,
                        new Resource("registry", Map.of("repository", repository)),
                        Map.of(
                                "actions",
                                Value.set(Set.of(Value.string("read")))
                        )
                ))
                .collect(Collectors.toSet()));

        try {
            backend.apply(desired);

            List<ArtifactKeeperApiPermission> permissions = client.getPermissions();
            assertEquals(2, permissions.size());
            assertEquals(
                    Set.copyOf(repositoryIds.values()),
                    permissions.stream()
                            .map(ArtifactKeeperApiPermission::targetId)
                            .collect(Collectors.toSet())
            );
            assertTrue(permissions.stream().allMatch(permission ->
                    permission.principalType().equals("user")
                            && permission.principalId().equals(userId)
                            && permission.targetType().equals("repository")
                            && permission.actions().equals(Set.of("read"))
            ));

            backend.apply(PactState.empty());
            assertTrue(client.getPermissions().isEmpty());
        }
        finally {
            backend.apply(PactState.empty());
        }
    }

    private static ArtifactKeeperConfig config(int perPage) {
        return ArtifactKeeperConfig.from(Map.of(
                "url", required("PACT_IT_ARTIFACT_KEEPER_URL"),
                "token", required("PACT_IT_ARTIFACT_KEEPER_TOKEN"),
                "per-page", Integer.toString(perPage),
                "api-profile", System.getenv().getOrDefault("PACT_IT_ARTIFACT_KEEPER_API_PROFILE", "legacy")
        ));
    }

    private static HttpResponse<String> apiRequest(
            ArtifactKeeperConfig config, String token, String method, String path, JsonNode body)
            throws Exception {
        var request = HttpRequest.newBuilder(URI.create(config.url().toString().replaceAll("/+$", "") + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode apiJson(ArtifactKeeperConfig config, String method, String path, JsonNode body)
            throws Exception {
        var response = apiRequest(config, config.token(), method, path, body);
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300,
                method + " " + path + " returned " + response.statusCode());
        return response.body().isBlank() ? MAPPER.nullNode() : MAPPER.readTree(response.body());
    }

    private static PactState state(
            String username,
            String repository,
            Set<String> actions
    ) {
        return new PactState(Set.of(
                new Access(
                        username,
                        new Resource("registry", Map.of("repository", repository)),
                        Map.of(
                                "actions",
                                io.github.pactproject.api.value.Value.set(
                                        actions.stream()
                                                .map(io.github.pactproject.api.value.Value::string)
                                                .collect(Collectors.toSet())
                                )
                        )
                )
        ));
    }

    private static void assertPermission(
            List<ArtifactKeeperApiPermission> permissions,
            String expectedUserId,
            String expectedRepositoryId,
            String expectedPrincipalType,
            Set<String> expectedActions
    ) {
        var permission = permissions.stream()
                .filter(candidate ->
                        candidate.principalType().equals(expectedPrincipalType)
                                && candidate.targetType().equals("repository"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected an Artifact Keeper permission"));
        assertEquals(expectedUserId, permission.principalId());
        assertEquals(expectedRepositoryId, permission.targetId());
        assertEquals(expectedActions, permission.actions());
        assertEquals(1, permissions.size());
    }

    private static String repositoryId(
            ArtifactKeeperHttpClient client,
            String repositoryName
    ) throws ArtifactKeeperClientException {
        return client.getRepositories().stream()
                .filter(repository -> repository.name().equals(repositoryName))
                .map(repository -> repository.id())
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Configured integration-test repository must already exist"
                ));
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
