package io.github.pactproject.artifactkeeper;

import io.github.pactproject.api.Access;
import io.github.pactproject.api.BackendTransaction;
import io.github.pactproject.api.PactState;
import io.github.pactproject.api.Resource;
import io.github.pactproject.artifactkeeper.api.ArtifactKeeperApiPermission;
import io.github.pactproject.artifactkeeper.client.ArtifactKeeperClientException;
import io.github.pactproject.artifactkeeper.client.ArtifactKeeperHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "pact.integration", matches = "true")
class ArtifactKeeperLiveIntegrationTest {
    @Test
    void reconcilesRepositoryPermissionsAgainstLiveApi()
            throws Exception, ArtifactKeeperClientException {
        ArtifactKeeperConfig config = ArtifactKeeperConfig.from(Map.of(
                "url", required("PACT_IT_ARTIFACT_KEEPER_URL"),
                "token", required("PACT_IT_ARTIFACT_KEEPER_TOKEN")
        ));
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

        ArtifactKeeperBackend backend = new ArtifactKeeperBackend("registry", client);
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
                    Set.of("read")
            );

            update = backend.prepare(initial, updated);
            update.apply();
            assertPermission(
                    client.getPermissions(),
                    userId,
                    repositoryId,
                    Set.of("read", "write")
            );

            backend.apply(updated);
            assertPermission(
                    client.getPermissions(),
                    userId,
                    repositoryId,
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
            Set<String> expectedActions
    ) {
        var permission = permissions.stream()
                .filter(candidate ->
                        candidate.principalType().equals("user")
                                && candidate.targetType().equals("repository"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected an Artifact Keeper permission"));
        assertEquals(expectedUserId, permission.principalId());
        assertEquals(expectedRepositoryId, permission.targetId());
        assertEquals(expectedActions, permission.actions());
        assertEquals(1, permissions.size());
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
