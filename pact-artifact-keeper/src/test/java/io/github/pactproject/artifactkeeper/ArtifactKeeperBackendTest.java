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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import io.github.pactproject.api.value.Value;

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

    @ParameterizedTest
    @EnumSource(ArtifactKeeperApiProfile.class)
    void createsServiceAccountsIdempotentlyWithProfileSpecificWireType(ArtifactKeeperApiProfile profile)
            throws Exception {
        var client = client(List.of(), List.of(REPOSITORY), List.of());
        var backend = new ArtifactKeeperBackend("ak", client, profile);
        var desired = state(access("svc-build", "repository-a", Set.of("read")));

        backend.apply(desired);
        var created = client.getPermissions().getFirst();
        assertEquals(profile == ArtifactKeeperApiProfile.LEGACY ? "user" : "service_account",
                created.principalType());
        assertTrue(client.getUsers().getFirst().serviceAccount());

        backend.apply(desired);
        assertEquals(List.of(created), client.getPermissions());
        assertEquals(1, client.createServiceAccountCalls());
        assertEquals(1, client.createPermissionCalls());
        assertEquals(0, client.deletePermissionCalls());

        backend.apply(PactState.empty());
        assertTrue(client.getPermissions().isEmpty());
        assertEquals(1, client.getUsers().size());
    }

    @Test
    void currentProfilePreservesLegacyGrantsAndRevokesEveryPhysicalRow() throws Exception {
        var account = new ArtifactKeeperApiUser("sa-1", "svc-build", true);
        var legacy = permission("legacy", account.id(), REPOSITORY.id(), Set.of("read"));
        var current = new ArtifactKeeperApiPermission(
                "current", "service_account", account.id(), "repository", REPOSITORY.id(), Set.of("read"));
        var client = client(List.of(account), List.of(REPOSITORY), List.of(legacy, current));
        var backend = new ArtifactKeeperBackend("ak", client, ArtifactKeeperApiProfile.CURRENT);

        backend.apply(state(access(account.username(), REPOSITORY.name(), Set.of("read"))));
        assertEquals(List.of(legacy, current), client.getPermissions());
        assertEquals(0, client.createPermissionCalls());
        assertEquals(0, client.deletePermissionCalls());

        backend.apply(PactState.empty());
        assertTrue(client.getPermissions().isEmpty());
        assertEquals(2, client.deletePermissionCalls());
    }

    @Test
    void resolvesExistingPrincipalTypesFromMetadataInsteadOfName() throws Exception {
        var human = new ArtifactKeeperApiUser("human", "svc-human", false);
        var account = new ArtifactKeeperApiUser("machine", "imported-bot", true);
        var client = client(List.of(human, account), List.of(REPOSITORY), List.of());
        var backend = new ArtifactKeeperBackend("ak", client, ArtifactKeeperApiProfile.CURRENT);

        backend.apply(state(
                access(human.username(), REPOSITORY.name(), Set.of("read")),
                access(account.username(), REPOSITORY.name(), Set.of("read"))
        ));
        assertTrue(client.getPermissions().stream().anyMatch(p ->
                p.principalId().equals(human.id()) && p.principalType().equals("user")));
        assertTrue(client.getPermissions().stream().anyMatch(p ->
                p.principalId().equals(account.id()) && p.principalType().equals("service_account")));
        assertEquals(0, client.createServiceAccountCalls());
    }

    @ParameterizedTest
    @EnumSource(ArtifactKeeperApiProfile.class)
    void compensationRestoresServiceAccountGrantAfterPartialFailure(ArtifactKeeperApiProfile profile)
            throws Exception {
        var client = client(List.of(), List.of(REPOSITORY), List.of());
        var backend = new ArtifactKeeperBackend("ak", client, profile);
        var previous = state(access("svc-build", REPOSITORY.name(), Set.of("read")));
        backend.apply(previous);
        var accountId = client.getUsers().getFirst().id();
        var transaction = backend.prepare(previous,
                state(access("svc-build", REPOSITORY.name(), Set.of("write"))));
        assertEquals(1, client.createPermissionCalls(), "prepare must not write");

        client.failNextCreatePermission();
        assertThrows(BackendOperationException.class, transaction::apply);
        assertTrue(client.getPermissions().isEmpty(), "old grant was deleted before create failed");
        transaction.rollback();

        var restored = client.getPermissions().getFirst();
        assertEquals(accountId, restored.principalId());
        assertEquals(profile == ArtifactKeeperApiProfile.LEGACY ? "user" : "service_account",
                restored.principalType());
        assertEquals(Set.of("read"), restored.actions());
        assertEquals(1, client.createServiceAccountCalls());
        backend.apply(previous);
        assertEquals(List.of(restored), client.getPermissions());
    }

    @Test
    void validatesAllDesiredReferencesBeforeDeletingGrants() {
        var existing = permission("keep", ALICE.id(), REPOSITORY.id(), Set.of("read"));
        var client = client(List.of(ALICE), List.of(REPOSITORY), List.of(existing));
        var backend = new ArtifactKeeperBackend("ak", client, ArtifactKeeperApiProfile.CURRENT);

        assertThrows(ValidationException.class, () -> backend.apply(
                state(access("missing-human", REPOSITORY.name(), Set.of("read")))));
        assertThrows(ValidationException.class, () -> backend.apply(
                state(access("svc-build", "missing-repo", Set.of("read")))));
        assertEquals(List.of(existing), client.getPermissions());
        assertEquals(0, client.deletePermissionCalls());
        assertEquals(0, client.createServiceAccountCalls());
    }

    @ParameterizedTest
    @ValueSource(strings = {"repository", "artifact", "project", "group", "system", "global", "future_scope"})
    void reconcilesEveryTargetTypeWithoutReinterpretingItsId(String targetType) throws Exception {
        String targetId = "11111111-1111-1111-1111-111111111111";
        var repository = new ArtifactKeeperApiRepository(targetId, "repository-a");
        var grant = new ArtifactKeeperApiPermission("existing", "user", ALICE.id(), targetType,
                targetId, Set.of("read"));
        var client = client(List.of(ALICE), List.of(repository), List.of(grant));
        var backend = new ArtifactKeeperBackend("ak", client, ArtifactKeeperApiProfile.CURRENT);
        var desired = typedState("alice", "user", targetType, targetId, Set.of("read"), Map.of());

        backend.apply(desired);
        assertEquals(List.of(grant), client.getPermissions());
        assertEquals(0, client.deletePermissionCalls());

        backend.apply(typedState("alice", "user", targetType, targetId, Set.of("read", "write"), Map.of()));
        assertEquals(targetType, client.getPermissions().getFirst().targetType());
        assertEquals(targetId, client.getPermissions().getFirst().targetId());
        backend.apply(PactState.empty());
        assertTrue(client.getPermissions().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"group", "anonymous"})
    void reconcilesNonUserPrincipals(String type) throws Exception {
        String id = type.equals("anonymous") ? "00000000-0000-0000-0000-000000000000"
                : "22222222-2222-2222-2222-222222222222";
        String targetId = "11111111-1111-1111-1111-111111111111";
        var client = client(List.of(), List.of(new ArtifactKeeperApiRepository(targetId, "repository-a")), List.of());
        var backend = new ArtifactKeeperBackend("ak", client, ArtifactKeeperApiProfile.CURRENT);
        var desired = typedState(id, type, "repository", targetId, Set.of("read"), Map.of());
        backend.apply(desired);
        var grant = client.getPermissions().getFirst();
        assertEquals(type, grant.principalType());
        assertEquals(id, grant.principalId());
        backend.apply(desired);
        assertEquals(List.of(grant), client.getPermissions());
        backend.apply(PactState.empty());
        assertTrue(client.getPermissions().isEmpty());
    }

    @Test
    void conditionalPermissionIsDistinctFromUnconditionalGrant() throws Exception {
        String targetId = "11111111-1111-1111-1111-111111111111";
        var conditions = Map.of("allowed_cidrs", Value.set(Set.of(Value.string("10.0.0.0/8"))));
        var grant = new ArtifactKeeperApiPermission("old", "user", ALICE.id(), "artifact", targetId,
                Set.of("read"), Map.copyOf(conditions));
        var client = client(List.of(ALICE), List.of(), List.of(grant));
        var backend = new ArtifactKeeperBackend("ak", client, ArtifactKeeperApiProfile.CURRENT);
        var previous = typedState("alice", "user", "artifact", targetId, Set.of("read"), Map.copyOf(conditions));
        backend.apply(previous);
        assertEquals(0, client.deletePermissionCalls());
        var transaction = backend.prepare(previous,
                typedState("alice", "user", "artifact", targetId, Set.of("read"), Map.of()));
        client.failNextCreatePermission();
        assertThrows(BackendOperationException.class, transaction::apply);
        transaction.rollback();
        assertEquals(conditions, client.getPermissions().getFirst().conditions());
    }

    private static PactState typedState(String principal, String principalType, String type, String id,
                                        Set<String> actions, Map<String, Value> conditions) {
        return state(new Access(principal, new Resource("ak", Map.of("type", type, "id", id)), Map.of(
                "principal-type", Value.string(principalType),
                "conditions", Value.object(conditions),
                "actions", Value.set(actions.stream().map(Value::string).collect(java.util.stream.Collectors.toSet()))
        )));
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
