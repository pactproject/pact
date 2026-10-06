package io.github.pactproject.artifactkeeper;

import io.github.pactproject.api.PactState;
import io.github.pactproject.api.Resource;
import io.github.pactproject.api.Access;
import io.github.pactproject.api.exception.BackendOperationException;
import io.github.pactproject.api.exception.ValidationException;
import io.github.pactproject.artifactkeeper.api.ArtifactKeeperApiPermission;
import io.github.pactproject.artifactkeeper.api.ArtifactKeeperApiRepository;
import io.github.pactproject.artifactkeeper.api.ArtifactKeeperApiUser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ArtifactKeeperBackendTest {

    private static final ArtifactKeeperApiUser ALICE =
            new ArtifactKeeperApiUser("user-1", "alice");

    private static final ArtifactKeeperApiUser BOB =
            new ArtifactKeeperApiUser("user-2", "bob");

    private static final ArtifactKeeperApiRepository REPOSITORY =
            new ArtifactKeeperApiRepository("repo-1", "repository-a");

    @Test
    void doesNothingWhenStateAlreadyMatches()
            throws Exception {

        ArtifactKeeperApiPermission permission =
                permission(
                        "permission-1",
                        "user-1",
                        "repo-1",
                        Set.of("read")
                );

        FakeArtifactKeeperClient client =
                client(
                        List.of(ALICE),
                        List.of(REPOSITORY),
                        List.of(permission)
                );

        ArtifactKeeperBackend backend =
                new ArtifactKeeperBackend("ak", client);

        backend.apply(
                state(
                        access(
                                "alice",
                                "repository-a",
                                Set.of("read")
                        )
                )
        );

        assertEquals(0, client.deletePermissionCalls());
        assertEquals(0, client.createPermissionCalls());
        assertEquals(0, client.createServiceAccountCalls());

        assertEquals(
                List.of(permission),
                client.getPermissions()
        );
    }

    @Test
    void createsMissingPermission()
            throws Exception {

        FakeArtifactKeeperClient client =
                client(
                        List.of(ALICE),
                        List.of(REPOSITORY),
                        List.of()
                );

        ArtifactKeeperBackend backend =
                new ArtifactKeeperBackend("ak", client);

        backend.apply(
                state(
                        access(
                                "alice",
                                "repository-a",
                                Set.of("read")
                        )
                )
        );

        assertEquals(0, client.deletePermissionCalls());
        assertEquals(1, client.createPermissionCalls());

        assertEquals(1, client.getPermissions().size());

        ArtifactKeeperApiPermission permission =
                client.getPermissions().get(0);

        assertEquals("user", permission.principalType());
        assertEquals("user-1", permission.principalId());
        assertEquals("repository", permission.targetType());
        assertEquals("repo-1", permission.targetId());
        assertEquals(Set.of("read"), permission.actions());
    }

    @Test
    void deletesExtraPermission()
            throws Exception {

        ArtifactKeeperApiPermission permission =
                permission(
                        "permission-1",
                        "user-1",
                        "repo-1",
                        Set.of("read")
                );

        FakeArtifactKeeperClient client =
                client(
                        List.of(ALICE),
                        List.of(REPOSITORY),
                        List.of(permission)
                );

        ArtifactKeeperBackend backend =
                new ArtifactKeeperBackend("ak", client);

        backend.apply(PactState.empty());

        assertEquals(1, client.deletePermissionCalls());
        assertEquals(0, client.createPermissionCalls());

        assertTrue(client.getPermissions().isEmpty());
    }

    @Test
    void replacesPermissionWhenActionsChange()
            throws Exception {

        ArtifactKeeperApiPermission permission =
                permission(
                        "permission-1",
                        "user-1",
                        "repo-1",
                        Set.of("read")
                );

        FakeArtifactKeeperClient client =
                client(
                        List.of(ALICE),
                        List.of(REPOSITORY),
                        List.of(permission)
                );

        ArtifactKeeperBackend backend =
                new ArtifactKeeperBackend("ak", client);

        backend.apply(
                state(
                        access(
                                "alice",
                                "repository-a",
                                Set.of("read", "write")
                        )
                )
        );

        assertEquals(1, client.deletePermissionCalls());
        assertEquals(1, client.createPermissionCalls());

        assertEquals(1, client.getPermissions().size());

        ArtifactKeeperApiPermission result =
                client.getPermissions().get(0);

        assertEquals(
                Set.of("read", "write"),
                result.actions()
        );
    }

    @Test
    void throwsValidationExceptionForMissingUser()
            throws Exception {

        FakeArtifactKeeperClient client =
                client(
                        List.of(),
                        List.of(REPOSITORY),
                        List.of()
                );

        ArtifactKeeperBackend backend =
                new ArtifactKeeperBackend("ak", client);

        ValidationException exception =
                assertThrows(
                        ValidationException.class,
                        () -> backend.apply(
                                state(
                                        access(
                                                "alice",
                                                "repository-a",
                                                Set.of("read")
                                        )
                                )
                        )
                );

        assertTrue(
                exception.getMessage()
                        .contains("Artifact Keeper user not found")
        );

        assertEquals(0, client.deletePermissionCalls());
        assertEquals(0, client.createPermissionCalls());
        assertEquals(0, client.createServiceAccountCalls());
    }

    @Test
    void createsServiceAccountForMissingSvcUser()
            throws Exception {

        FakeArtifactKeeperClient client =
                client(
                        List.of(),
                        List.of(REPOSITORY),
                        List.of()
                );

        ArtifactKeeperBackend backend =
                new ArtifactKeeperBackend("ak", client);

        backend.apply(
                state(
                        access(
                                "svc-build",
                                "repository-a",
                                Set.of("read")
                        )
                )
        );

        assertEquals(1, client.createServiceAccountCalls());
        assertEquals(1, client.createPermissionCalls());

        assertEquals(1, client.getUsers().size());
        assertEquals("svc-build", client.getUsers().get(0).username());

        assertEquals(1, client.getPermissions().size());

        ArtifactKeeperApiPermission permission =
                client.getPermissions().get(0);

        assertEquals(
                client.getUsers().get(0).id(),
                permission.principalId()
        );
        assertEquals("user", permission.principalType());
    }

    @Test
    void throwsValidationExceptionForMissingRepository()
            throws Exception {

        FakeArtifactKeeperClient client =
                client(
                        List.of(ALICE),
                        List.of(),
                        List.of()
                );

        ArtifactKeeperBackend backend =
                new ArtifactKeeperBackend("ak", client);

        ValidationException exception =
                assertThrows(
                        ValidationException.class,
                        () -> backend.apply(
                                state(
                                        access(
                                                "alice",
                                                "repository-a",
                                                Set.of("read")
                                        )
                                )
                        )
                );

        assertTrue(
                exception.getMessage()
                        .contains(
                                "Artifact Keeper repository not found"
                        )
        );

        assertEquals(0, client.deletePermissionCalls());
        assertEquals(0, client.createPermissionCalls());
    }

    @Test
    void throwsBackendOperationExceptionWhenDeletingPermissionFails()
            throws Exception {

        ArtifactKeeperApiPermission permission =
                permission(
                        "permission-1",
                        "user-1",
                        "repo-1",
                        Set.of("read")
                );

        FakeArtifactKeeperClient client =
                client(
                        List.of(ALICE),
                        List.of(REPOSITORY),
                        List.of(permission)
                );

        client.failOnDeletePermission("permission-1");

        ArtifactKeeperBackend backend =
                new ArtifactKeeperBackend("ak", client);

        BackendOperationException exception =
                assertThrows(
                        BackendOperationException.class,
                        () -> backend.apply(PactState.empty())
                );

        assertTrue(
                exception.getMessage()
                        .contains("Failed to delete Artifact Keeper permission")
        );

        assertEquals(1, client.deletePermissionCalls());
        assertEquals(0, client.createPermissionCalls());
    }

    @Test
    void throwsBackendOperationExceptionWhenCreatingPermissionFails()
            throws Exception {

        FakeArtifactKeeperClient client =
                client(
                        List.of(ALICE),
                        List.of(REPOSITORY),
                        List.of()
                );

        client.failOnCreatePermission();

        ArtifactKeeperBackend backend =
                new ArtifactKeeperBackend("ak", client);

        BackendOperationException exception =
                assertThrows(
                        BackendOperationException.class,
                        () -> backend.apply(
                                state(
                                        access(
                                                "alice",
                                                "repository-a",
                                                Set.of("read")
                                        )
                                )
                        )
                );

        assertTrue(
                exception.getMessage()
                        .contains(
                                "Failed to create Artifact Keeper permission"
                        )
        );

        assertEquals(1, client.createPermissionCalls());
        assertTrue(client.getPermissions().isEmpty());
    }

    @Test
    void throwsBackendOperationExceptionWhenCreatingServiceAccountFails()
            throws Exception {

        FakeArtifactKeeperClient client =
                client(
                        List.of(),
                        List.of(REPOSITORY),
                        List.of()
                );

        client.failOnCreateServiceAccount();

        ArtifactKeeperBackend backend =
                new ArtifactKeeperBackend("ak", client);

        BackendOperationException exception =
                assertThrows(
                        BackendOperationException.class,
                        () -> backend.apply(
                                state(
                                        access(
                                                "svc-build",
                                                "repository-a",
                                                Set.of("read")
                                        )
                                )
                        )
                );

        assertTrue(
                exception.getMessage()
                        .contains(
                                "Failed to create Artifact Keeper service account"
                        )
        );

        assertEquals(1, client.createServiceAccountCalls());
        assertEquals(0, client.createPermissionCalls());
    }

    private static FakeArtifactKeeperClient client(
            List<ArtifactKeeperApiUser> users,
            List<ArtifactKeeperApiRepository> repositories,
            List<ArtifactKeeperApiPermission> permissions
    ) {
        return new FakeArtifactKeeperClient(
                users,
                repositories,
                permissions
        );
    }

    private static ArtifactKeeperApiPermission permission(
            String id,
            String userId,
            String repositoryId,
            Set<String> actions
    ) {
        return new ArtifactKeeperApiPermission(
                id,
                "user",
                userId,
                "repository",
                repositoryId,
                actions
        );
    }

    private static PactState state(
            Access... accesses
    ) {
        return new PactState(Set.of(accesses));
    }

    private static Access access(
            String principal,
            String repository,
            Set<String> actions
    ) {
        return new Access(
                principal,
                new Resource(
                        "artifact-keeper",
                        Map.of("repository", repository)
                ),
                Map.of(
                        "actions",
                        new io.github.pactproject.api.value.SetValue(
                                actions.stream()
                                        .map(
                                                io.github.pactproject.api.value.StringValue::new
                                        )
                                        .collect(java.util.stream.Collectors.toSet())
                        )
                )
        );
    }
}
