package io.github.pactproject.artifactkeeper;

import io.github.pactproject.api.Access;
import io.github.pactproject.api.PactState;
import io.github.pactproject.api.Resource;
import io.github.pactproject.api.exception.BackendOperationException;
import io.github.pactproject.api.exception.ValidationException;
import io.github.pactproject.api.value.Value;
import io.github.pactproject.artifactkeeper.api.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ArtifactKeeperNameResolutionTest {
    private static final String GROUP = "11111111-1111-1111-1111-111111111111";
    private static final String TARGET = "22222222-2222-2222-2222-222222222222";
    private static final String OTHER = "33333333-3333-3333-3333-333333333333";
    private static final ArtifactKeeperApiUser ALICE = new ArtifactKeeperApiUser("user-1", "alice");
    private static final ArtifactKeeperApiRepository REPO = new ArtifactKeeperApiRepository("repo-1", "repo-a", "Repository A");

    @Test
    void resolvesGroupSubjectsAndTargetsAndPreservesIdsAcrossReferenceStyles() throws Exception {
        var client = client(List.of());
        client.groups(List.of(new ArtifactKeeperApiNamedObject(GROUP, "readers"),
                new ArtifactKeeperApiNamedObject(TARGET, "team-to-administer")));
        var backend = backend(client);
        backend.apply(state(access("readers", "group", Map.of("type", "group", "name", "team-to-administer"), "admin")));
        var grant = client.getPermissions().getFirst();
        assertEquals(GROUP, grant.principalId());
        assertEquals(TARGET, grant.targetId());
        assertEquals("group", grant.targetType());
        assertEquals(1, client.groupReads(), "one catalog snapshot serves both subject and object names");
        backend.apply(state(access(GROUP, "group", Map.of("type", "group", "id", TARGET), "admin")));
        assertEquals(List.of(grant), client.getPermissions());
        assertEquals(1, client.groupReads(), "explicit IDs do not require the group catalog");
        backend.apply(PactState.empty());
        assertTrue(client.getPermissions().isEmpty());
    }

    @Test
    void resolvesProjectKeyOrDisplayNameAndUserIdOrUsernameBeforeMerging() throws Exception {
        var client = client(List.of());
        client.projects(List.of(new ArtifactKeeperApiNamedObject(TARGET, "payments", "Payments team")));
        var backend = backend(client);
        backend.apply(state(
                access("alice", "user", Map.of("type", "project", "name", "payments"), "read"),
                access(ALICE.id(), "user", Map.of("type", "project", "name", "Payments team"), "write")));
        var grant = client.getPermissions().getFirst();
        assertEquals(TARGET, grant.targetId());
        assertEquals(ALICE.id(), grant.principalId());
        assertEquals(Set.of("read", "write"), grant.actions());
        backend.apply(state(access("alice", "user", Map.of("type", "project", "id", TARGET), "read", "write")));
        assertEquals(List.of(grant), client.getPermissions());
    }

    @Test
    void scopesArtifactsByRepositoryAndVersionAndCachesOnlyOneApply() throws Exception {
        var client = new FakeArtifactKeeperClient(List.of(ALICE), List.of(REPO,
                new ArtifactKeeperApiRepository("repo-2", "repo-b")), List.of());
        client.artifact("repo-a", "org/app.jar", "release", TARGET);
        client.artifact("repo-b", "org/app.jar", null, OTHER);
        var backend = backend(client);
        var desired = state(
                access("alice", "user", Map.of("type", "artifact", "repository", "Repository A",
                        "path", "org/app.jar", "version", "release"), "read"),
                access(ALICE.id(), "user", Map.of("type", "artifact", "repository", "repo-a",
                        "name", "org/app.jar", "version", "release"), "write"),
                access("alice", "user", Map.of("type", "artifact", "repository", "repo-b", "path", "org/app.jar"), "read"));
        backend.apply(desired);
        assertEquals(2, client.getPermissions().size());
        assertEquals(2, client.artifactReads());
        assertEquals(Set.of("read", "write"), client.getPermissions().stream()
                .filter(p -> p.targetId().equals(TARGET)).findFirst().orElseThrow().actions());
        var grants = client.getPermissions();
        backend.apply(desired);
        assertEquals(grants, client.getPermissions());
        assertEquals(4, client.artifactReads(), "a new apply must re-resolve external names");
    }

    @Test
    void ambiguousAndMissingNamesAndCatalogErrorsNeverDeleteExistingGrants() {
        var old = new ArtifactKeeperApiPermission("keep", "user", ALICE.id(), "project", TARGET, Set.of("read"));
        var client = client(List.of(old));
        client.projects(List.of(new ArtifactKeeperApiNamedObject(TARGET, "one", "duplicate"),
                new ArtifactKeeperApiNamedObject(OTHER, "two", "duplicate")));
        var backend = backend(client);
        assertThrows(ValidationException.class, () -> backend.apply(state(
                access("svc-new", "service_account", Map.of("type", "project", "name", "duplicate"), "read"))));
        assertThrows(ValidationException.class, () -> backend.apply(state(
                access("alice", "user", Map.of("type", "project", "name", "missing"), "read"))));
        client.failCatalogReads();
        assertThrows(BackendOperationException.class, () -> backend.apply(state(
                access("alice", "user", Map.of("type", "project", "name", "one"), "read"))));
        assertEquals(List.of(old), client.getPermissions());
        assertEquals(0, client.deletePermissionCalls());
        assertEquals(0, client.createPermissionCalls());
        assertEquals(0, client.createServiceAccountCalls());
    }

    @Test
    void repositoryDisplayNameIsSupportedAndIdsBypassAmbiguousDisplayNames() throws Exception {
        var client = client(List.of());
        var backend = backend(client);
        backend.apply(state(access(ALICE.id(), "user", Map.of("type", "repository", "name", "Repository A"), "read")));
        assertEquals(REPO.id(), client.getPermissions().getFirst().targetId());
        var ambiguous = new FakeArtifactKeeperClient(List.of(ALICE), List.of(REPO,
                new ArtifactKeeperApiRepository("repo-2", "repo-b", "repo-a")), List.of());
        var ambiguousBackend = backend(ambiguous);
        assertThrows(ValidationException.class, () -> ambiguousBackend.apply(state(
                access("alice", "user", Map.of("name", "repo-a"), "read"))));
        // Existing IDs are real UUIDs in HTTP; use a UUID fixture for this branch.
        var idClient = new FakeArtifactKeeperClient(List.of(ALICE), List.of(
                new ArtifactKeeperApiRepository(TARGET, "repo-a", "Same"),
                new ArtifactKeeperApiRepository(OTHER, "repo-b", "repo-a")), List.of());
        backend(idClient).apply(state(access("alice", "user", Map.of("type", "repository", "id", TARGET), "read")));
        assertEquals(TARGET, idClient.getPermissions().getFirst().targetId());
    }

    @Test
    void compensationRestoresNamedArtifactGrantAfterPartialFailure() throws Exception {
        var client = client(List.of());
        client.artifact("repo-a", "app.jar", null, TARGET);
        var backend = backend(client);
        var previous = state(access("alice", "user", Map.of("type", "artifact", "repository", "repo-a", "path", "app.jar"), "read"));
        backend.apply(previous);
        var transaction = backend.prepare(previous,
                state(access("alice", "user", Map.of("type", "artifact", "id", TARGET), "write")));
        client.failNextCreatePermission();
        assertThrows(BackendOperationException.class, transaction::apply);
        transaction.rollback();
        var restored = client.getPermissions().getFirst();
        assertEquals(TARGET, restored.targetId());
        assertEquals(Set.of("read"), restored.actions());
        assertEquals("artifact", restored.targetType());
    }

    @Test
    void idsRemainUsableWithoutOptionalCatalogsAndAbsentUserIdsAreNotCreated() throws Exception {
        var client = client(List.of());
        client.failCatalogReads();
        var backend = backend(client);
        backend.apply(state(access(GROUP, "group", Map.of("type", "project", "id", TARGET), "read")));
        assertEquals(TARGET, client.getPermissions().getFirst().targetId());
        assertThrows(ValidationException.class, () -> backend.apply(state(
                access(OTHER, "user", Map.of("name", "repo-a"), "read"))));
        assertEquals(0, client.createServiceAccountCalls());
    }

    @Test
    void serviceAccountIdUsesMetadataAndDoesNotEnsureAnotherAccount() throws Exception {
        var account = new ArtifactKeeperApiUser(GROUP, "svc-build", true);
        var client = new FakeArtifactKeeperClient(List.of(account), List.of(REPO), List.of());
        backend(client).apply(state(access(GROUP, "user", Map.of("name", "repo-a"), "read")));
        assertEquals("service_account", client.getPermissions().getFirst().principalType());
        assertEquals(GROUP, client.getPermissions().getFirst().principalId());
        assertEquals(0, client.createServiceAccountCalls());
    }

    @Test
    void rejectsConditionConflictsAfterResolvingAliasesBeforeWrites() {
        var client = client(List.of());
        client.projects(List.of(new ArtifactKeeperApiNamedObject(TARGET, "payments", "Payments team")));
        var one = access("alice", "user", Map.of("type", "project", "name", "payments"), "read");
        var second = access(ALICE.id(), "user", Map.of("type", "project", "id", TARGET), "write");
        var attributes = new java.util.HashMap<>(second.attributes());
        attributes.put("allowed_cidrs", Value.set(Set.of(Value.string("10.0.0.0/8"))));
        var conditional = new Access(second.principal(), second.resource(), attributes);
        assertThrows(ValidationException.class, () -> backend(client).apply(state(one, conditional)));
        assertEquals(0, client.createPermissionCalls());
        assertEquals(0, client.deletePermissionCalls());
    }

    @Test
    void legacyRejectsUnsupportedNamedFeaturesBeforeWrites() {
        var client = client(List.of());
        var backend = new ArtifactKeeperBackend("company-registry", client, ArtifactKeeperApiProfile.LEGACY);
        assertThrows(ValidationException.class, () -> backend.apply(state(
                access("alice", "user", Map.of("type", "project", "name", "payments"), "read"))));
        assertThrows(ValidationException.class, () -> backend.apply(state(
                access("alice", "user", Map.of("type", "artifact", "repository", "repo-a", "path", "app.jar", "version", "1"), "read"))));
        assertEquals(0, client.createPermissionCalls());
        assertEquals(0, client.artifactReads());
    }

    @Test
    void nestedConditionsFailBeforeExistingPermissionsAreChanged() {
        var existing = new ArtifactKeeperApiPermission("keep", "user", ALICE.id(), "repository", REPO.id(), Set.of("read"));
        var client = client(List.of(existing));
        Map<String, Value> attributes = Map.of("permissions", Value.set(Set.of(Value.string("read"))),
                "conditions", Value.object(Map.of("allowed_cidrs", Value.set(Set.of(Value.string("10.0.0.0/8"))))));
        assertThrows(ValidationException.class, () -> backend(client).apply(state(new Access("alice",
                new Resource("company-registry", Map.of("name", "repo-a")), attributes))));
        assertEquals(List.of(existing), client.getPermissions());
        assertEquals(0, client.createPermissionCalls());
        assertEquals(0, client.deletePermissionCalls());
    }

    private static FakeArtifactKeeperClient client(List<ArtifactKeeperApiPermission> permissions) {
        return new FakeArtifactKeeperClient(List.of(ALICE), List.of(REPO), permissions);
    }

    private static ArtifactKeeperBackend backend(FakeArtifactKeeperClient client) {
        return new ArtifactKeeperBackend("company-registry", client, ArtifactKeeperApiProfile.CURRENT);
    }

    private static PactState state(Access... accesses) { return new PactState(Set.of(accesses)); }

    private static Access access(String principal, String type, Map<String, String> target, String... actions) {
        return new Access(principal, new Resource("company-registry", target), Map.of(
                "principal-type", Value.string(type), "permissions", Value.set(java.util.Arrays.stream(actions)
                .map(Value::string).collect(java.util.stream.Collectors.toSet()))));
    }
}
