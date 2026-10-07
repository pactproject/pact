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
import static org.junit.jupiter.api.Assertions.assertThrows;

class PostgreSqlBackendTest {
    @Test
    void appliesDesiredStateAndCompensatesToSnapshot() throws Exception {
        FakeClient client = new FakeClient();
        Grant existing = grant("analytics", "alice", Privilege.CONNECT);
        client.actual.add(existing);
        Grant unrelated = grant("unmanaged", "bob", Privilege.CREATE);
        client.actual.add(unrelated);
        PostgreSqlBackend backend = new PostgreSqlBackend("postgres", client);

        PactState previous = state("alice", "analytics", "CONNECT");
        PactState desired = state("alice", "analytics", "CREATE");
        BackendTransaction transaction = backend.prepare(previous, desired);

        transaction.apply();
        assertEquals(
                Set.of(
                        grant("analytics", "alice", Privilege.CREATE),
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

        backend.prepare(previous, desired).apply();
        assertEquals(Set.of(), client.actual);
    }

    @Test
    void rejectsInvalidDesiredStateBeforeChangingDatabase() {
        FakeClient client = new FakeClient();
        PostgreSqlBackend backend = new PostgreSqlBackend("postgres", client);
        PactState invalid = state("alice", "analytics", "SELECT");

        assertThrows(
                ValidationException.class,
                () -> backend.prepare(PactState.empty(), invalid)
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
                () -> backend.prepare(
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

        backend.prepare(
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

        backend.prepare(
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
        BackendTransaction transaction = backend.prepare(
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
        private Set<Identity> desiredIdentities = Set.of();
        private final List<String> operations = new ArrayList<>();
        private boolean failOnRead;
        private int identityReconciliationCount;

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
        ) {
            operations.add("grants");
            actual.removeIf(grant -> databases.contains(grant.target().database()));
            actual.addAll(desired);
            actualDefaults.removeIf(
                    grant -> defaultScopes.contains(grant.scope())
            );
            actualDefaults.addAll(desiredDefaults);
        }
    }
}
