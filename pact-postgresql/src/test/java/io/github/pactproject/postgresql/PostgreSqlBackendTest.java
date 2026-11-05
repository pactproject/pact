package io.github.pactproject.postgresql;

import io.github.pactproject.api.Access;
import io.github.pactproject.api.BackendTransaction;
import io.github.pactproject.api.Identity;
import io.github.pactproject.api.PactState;
import io.github.pactproject.api.Resource;
import io.github.pactproject.api.exception.BackendOperationException;
import io.github.pactproject.api.exception.ValidationException;
import io.github.pactproject.api.value.Value;
import io.github.pactproject.postgresql.api.PostgreSqlClient;
import io.github.pactproject.postgresql.api.PostgreSqlClientException;
import io.github.pactproject.postgresql.model.DefaultPrivilegeGrant;
import io.github.pactproject.postgresql.model.DefaultPrivilegeOverride;
import io.github.pactproject.postgresql.model.DefaultPrivilegeScope;
import io.github.pactproject.postgresql.model.Grant;
import io.github.pactproject.postgresql.model.Ownership;
import io.github.pactproject.postgresql.model.GrantTarget;
import io.github.pactproject.postgresql.model.Privilege;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PostgreSqlBackendTest {
    @Test
    void changedGrantReferencesAfterPreparationPreventIdentityAndAclWrites() throws Exception {
        var client = new FakeClient();
        var backend = new PostgreSqlBackend("postgres", client);
        var initial = state("alice", "app", "CONNECT");
        var desired = new PactState(initial.accesses(), Set.of(new Identity("postgres", "new-role", true, null, null, null)));
        var pending = backend.prepareTransaction(PactState.empty(), desired);
        assertEquals(1, client.referenceChecks);
        client.failReferenceValidation = true;
        assertThrows(BackendOperationException.class, pending::apply);
        assertEquals(2, client.referenceChecks);
        assertTrue(client.operations.isEmpty());
        assertTrue(client.desiredIdentities.isEmpty());
        assertThrows(BackendOperationException.class, () -> backend.prepareTransaction(PactState.empty(), desired));
        assertTrue(client.operations.isEmpty());
    }

    @Test
    void ownershipPreparationIsReadOnlyAndRollbackRestoresOnlyDeclaredOwners() throws Exception {
        var client = new FakeClient();
        var backend = new PostgreSqlBackend("postgres", client);
        var target = new GrantTarget("app", "sales", "orders", null);
        var previous = ownershipState("a", target);
        var desired = ownershipState("b", target);
        var transaction = backend.prepareTransaction(previous, desired);
        assertTrue(client.actualOwners.isEmpty());
        assertTrue(client.operations.isEmpty());
        transaction.apply();
        assertEquals("b", client.actualOwners.get(target));
        transaction.rollback();
        assertEquals("a", client.actualOwners.get(target));

        var firstAssignment = backend.prepareTransaction(PactState.empty(), desired);
        firstAssignment.apply();
        firstAssignment.rollback();
        assertEquals("b", client.actualOwners.get(target), "Unspecified previous ownership must not be invented");
        backend.prepareTransaction(desired, PactState.empty()).apply();
        assertEquals("b", client.actualOwners.get(target), "Removing ownership must retain the owner");
    }

    @Test
    void ownershipReferenceFailurePreventsIdentityAndGrantWrites() {
        var client = new FakeClient();
        client.failOwnershipValidation = true;
        var target = new GrantTarget("app", "sales", "orders", null);
        var desired = ownershipState("owner", target);
        assertThrows(BackendOperationException.class,
                () -> new PostgreSqlBackend("postgres", client).prepareTransaction(PactState.empty(), desired));
        assertTrue(client.operations.isEmpty());
    }

    private static PactState ownershipState(String role, GrantTarget target) {
        return new PactState(Set.of(new Access(role, new Resource("postgres", Map.of(
                "database", target.database(), "schema", target.schema(), "table", target.table())),
                Map.of("ownership", Value.set(Set.of(Value.string("table")))))));
    }

    @Test
    void recoveryScopeIncludesFailedDatabaseWhenSourceMovesElsewhere() throws Exception {
        var client = new FakeClient();
        var outside = grant("outside", "alice", Privilege.CONNECT);
        client.actual.addAll(Set.of(grant("a", "alice", Privilege.CONNECT),
                grant("b", "alice", Privilege.CREATE), outside));
        var previous = state("alice", "a", "CONNECT");
        var desired = state("alice", "c", "CREATE");
        var transaction = new PostgreSqlBackend("postgres", client).prepareTransaction(previous, desired,
                Set.of(state("alice", "b", "CREATE")));
        assertEquals(3, client.actual.size(), "Recovery preparation must remain read-only");
        transaction.apply();
        assertEquals(Set.of(grant("c", "alice", Privilege.CREATE), outside), client.actual);
        transaction.rollback();
        assertEquals(Set.of(grant("a", "alice", Privilege.CONNECT), outside), client.actual);
    }

    @Test
    void compensatesCommittedDatabaseWhenLaterDatabaseFailsAndAllowsRetry() throws Exception {
        var client = new FakeClient();
        var previous = state("alice", "a-old", "CONNECT");
        var desired = state("alice", "z-new", "CREATE");
        var oldGrant = grant("a-old", "alice", Privilege.CONNECT);
        var outside = grant("outside", "bob", Privilege.CONNECT);
        client.actual.addAll(Set.of(oldGrant, outside));
        var backend = new PostgreSqlBackend("postgres", client);
        var transaction = backend.prepareTransaction(previous, desired);
        assertEquals(Set.of(oldGrant, outside), client.actual, "Preparation must be read-only");
        client.failBeforeDatabase = "z-new";

        assertThrows(BackendOperationException.class, transaction::apply);
        assertEquals(Set.of(outside), client.actual, "The first database already committed its revocation");
        transaction.rollback();
        assertEquals(Set.of(oldGrant, outside), client.actual);
        backend.prepareTransaction(previous, desired).apply();
        assertEquals(Set.of(grant("z-new", "alice", Privilege.CREATE), outside), client.actual);
    }

    @Test
    void rollbackRestoresDeclaredStateAndCleansAttemptedDatabaseInsteadOfSnapshot() throws Exception {
        FakeClient client = new FakeClient();
        Grant existing = grant("analytics", "alice", Privilege.CONNECT);
        client.actual.add(existing);
        client.actual.add(grant("analytics", "alice", Privilege.CREATE));
        Grant unrelated = grant("unmanaged", "bob", Privilege.CREATE);
        client.actual.add(unrelated);
        PostgreSqlBackend backend = new PostgreSqlBackend("postgres", client);

        PactState previous = state("alice", "analytics", "CONNECT");
        PactState desired = state("alice", "new-database", "CREATE");
        BackendTransaction transaction = backend.prepareTransaction(previous, desired);

        transaction.apply();
        assertEquals(
                Set.of(
                        grant("new-database", "alice", Privilege.CREATE),
                        unrelated
                ),
                client.actual
        );

        transaction.rollback();
        assertEquals(Set.of(existing, unrelated), client.actual);
    }

    @Test
    void removesStaleGrantsWhenDesiredDatabaseHasNoPermissions() throws Exception {
        FakeClient client = new FakeClient();
        client.actual.add(grant("analytics", "alice", Privilege.CONNECT));
        PostgreSqlBackend backend = new PostgreSqlBackend("postgres", client);

        PactState previous = state("alice", "analytics", "CONNECT");
        PactState desired = new PactState(Set.of(new Access(
                "alice",
                new Resource("postgres", Map.of("database", "analytics")),
                Map.of()
        )));

        backend.prepareTransaction(previous, desired).apply();
        assertEquals(Set.of(), client.actual);
    }

    @Test
    void rejectsInvalidDesiredStateBeforeChangingDatabase() {
        FakeClient client = new FakeClient();
        PostgreSqlBackend backend = new PostgreSqlBackend("postgres", client);
        PactState invalid = state("alice", "analytics", "SELECT");

        assertThrows(
                ValidationException.class,
                () -> backend.prepareTransaction(PactState.empty(), invalid)
        );
        assertEquals(Set.of(), client.actual);
    }

    @Test
    void wrapsClientFailuresDuringPrepare() {
        FakeClient client = new FakeClient();
        client.failOnRead = true;
        PostgreSqlBackend backend = new PostgreSqlBackend("postgres", client);

        assertThrows(
                BackendOperationException.class,
                () -> backend.prepareTransaction(
                        PactState.empty(),
                        state("alice", "analytics", "CONNECT")
                )
        );
    }

    @Test
    void reconcilesIdentityOnlyStateWithoutGrantWork() throws Exception {
        FakeClient client = new FakeClient();
        PostgreSqlBackend backend = new PostgreSqlBackend("postgres", client);
        Identity identity = new Identity(
                "postgres",
                "alice",
                true,
                null,
                null,
                null
        );

        backend.prepareTransaction(
                PactState.empty(),
                new PactState(Set.of(), Set.of(identity))
        ).apply();

        assertEquals(Set.of(identity), client.desiredIdentities);
        assertEquals(Set.of(), client.actual);
    }

    @Test
    void reconcilesIdentitiesBeforeGrantSynchronization() throws Exception {
        FakeClient client = new FakeClient();
        PostgreSqlBackend backend = new PostgreSqlBackend("postgres", client);
        Identity identity = new Identity(
                "postgres",
                "alice",
                true,
                "default/credentials#password",
                "2",
                new io.github.pactproject.api.SecretValue("rotated")
        );

        backend.prepareTransaction(
                PactState.empty(),
                new PactState(
                        state("alice", "analytics", "CONNECT").accesses(),
                        Set.of(identity)
                )
        ).apply();

        assertEquals(List.of("identities", "grants"), client.operations);
    }

    @Test
    void doesNotCompensateIdentityChangesWhenGrantTransactionRollsBack()
            throws Exception {
        FakeClient client = new FakeClient();
        PostgreSqlBackend backend = new PostgreSqlBackend("postgres", client);
        Identity identity = new Identity(
                "postgres",
                "alice",
                true,
                null,
                null,
                null
        );
        BackendTransaction transaction = backend.prepareTransaction(
                PactState.empty(),
                new PactState(Set.of(), Set.of(identity))
        );

        transaction.apply();
        transaction.rollback();

        assertEquals(1, client.identityReconciliationCount);
        assertEquals(Set.of(identity), client.desiredIdentities);
    }

    private static PactState state(
            String role,
            String database,
            String... permissions
    ) {
        return new PactState(Set.of(new Access(
                role,
                new Resource("postgres", Map.of("database", database)),
                Map.of(
                        "permissions",
                        Value.object(Map.of(
                                "database",
                                Value.set(java.util.Arrays.stream(permissions)
                                        .map(Value::string)
                                        .collect(Collectors.toSet()))
                        ))
                )
        )));
    }

    private static Grant grant(
            String database,
            String role,
            Privilege privilege
    ) {
        return new Grant(new GrantTarget(database, null, null, null), role, privilege);
    }

    private static final class FakeClient implements PostgreSqlClient {
        private final Set<Grant> actual = new HashSet<>();
        private final Set<DefaultPrivilegeGrant> actualDefaults = new HashSet<>();
        private final Map<GrantTarget, String> actualOwners = new java.util.HashMap<>();
        private boolean failOwnershipValidation;
        private boolean failReferenceValidation;
        private int referenceChecks;
        private Set<Identity> desiredIdentities = Set.of();
        private final List<String> operations = new ArrayList<>();
        private boolean failOnRead;
        private String failBeforeDatabase;
        private int identityReconciliationCount;

        @Override
        public void validateReferences(Set<Grant> grants, Set<DefaultPrivilegeGrant> defaults,
                Set<Identity> identities) throws PostgreSqlClientException {
            referenceChecks++;
            if (failReferenceValidation) throw new PostgreSqlClientException("Desired reference disappeared");
        }

        @Override
        public void validateOwnerships(Set<Ownership> desired, Set<String> ensuredRoles)
                throws PostgreSqlClientException {
            if (failOwnershipValidation) throw new PostgreSqlClientException("Missing ownership reference");
        }

        @Override
        public void synchronize(Set<String> databases, Set<Grant> desired,
                Set<DefaultPrivilegeGrant> defaults, Set<DefaultPrivilegeScope> scopes,
                Set<DefaultPrivilegeOverride> overrides, Set<Ownership> ownerships)
                throws PostgreSqlClientException {
            ownerships.forEach(owner -> actualOwners.put(owner.target(), owner.role()));
            synchronize(databases, desired, defaults, scopes, overrides);
        }

        @Override
        public Set<Grant> getManagedGrants(Set<String> databases)
                throws PostgreSqlClientException {
            if (failOnRead) {
                throw new PostgreSqlClientException("test read failure");
            }
            return actual.stream()
                    .filter(grant -> databases.contains(grant.target().database()))
                    .collect(Collectors.toUnmodifiableSet());
        }

        @Override
        public Set<DefaultPrivilegeGrant> getManagedDefaultPrivileges(
                Set<DefaultPrivilegeScope> scopes
        ) {
            return actualDefaults.stream()
                    .filter(grant -> scopes.contains(grant.scope()))
                    .collect(Collectors.toUnmodifiableSet());
        }

        @Override
        public void reconcileIdentities(
                Set<Identity> previous,
                Set<Identity> desired
        ) {
            operations.add("identities");
            identityReconciliationCount++;
            desiredIdentities = Set.copyOf(desired);
        }

        @Override
        public void synchronize(
                Set<String> databases,
                Set<Grant> desired,
                Set<DefaultPrivilegeGrant> desiredDefaults,
                Set<DefaultPrivilegeScope> defaultScopes,
                Set<DefaultPrivilegeOverride> overrides
        ) throws PostgreSqlClientException {
            operations.add("grants");
            for (String database : new java.util.TreeSet<>(databases)) {
                if (database.equals(failBeforeDatabase)) {
                    failBeforeDatabase = null;
                    throw new PostgreSqlClientException("Later database unavailable");
                }
                actual.removeIf(grant -> database.equals(grant.database()));
                desired.stream().filter(grant -> database.equals(grant.database())).forEach(actual::add);
                actualDefaults.removeIf(grant -> database.equals(grant.scope().database())
                        && defaultScopes.contains(grant.scope()));
                desiredDefaults.stream().filter(grant -> database.equals(grant.scope().database()))
                        .forEach(actualDefaults::add);
            }
        }
    }
}
