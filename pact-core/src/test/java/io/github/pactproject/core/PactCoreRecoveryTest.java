package io.github.pactproject.core;

import io.github.pactproject.api.*;
import io.github.pactproject.api.exception.*;
import io.github.pactproject.core.exception.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PactCoreRecoveryTest {
    @ParameterizedTest
    @ValueSource(strings = {"a", "c", "empty"})
    void failedRollbackForcesCurrentSourceStateAndCleansEveryAttemptedScope(String target) throws Exception {
        var backend = new ScopedBackend("db");
        var untouched = new ScopedBackend("other");
        var core = new PactCore(List.of(backend, untouched));
        var a = access("db", "a");
        var b = access("db", "b");
        var stable = access("other", "stable");
        core.apply(state(a, stable));
        backend.actual.add(access("db", "outside"));
        backend.failApply = backend.failRollback = true;
        assertThrows(RollbackException.class, () -> core.apply(state(b, stable)));
        assertTrue(backend.actual.contains(b));

        var current = target.equals("empty") ? state(stable) : state(access("db", target), stable);
        core.apply(current);
        assertEquals(target.equals("empty") ? Set.of(access("db", "outside"))
                : Set.of(access("db", target), access("db", "outside")), backend.actual);
        assertEquals(Set.of(state(a), state(b)), backend.lastRecovery);
        assertEquals(1, untouched.applies, "An ordinary unchanged backend must still be skipped");
        int applied = backend.applies;
        core.apply(current);
        assertEquals(applied, backend.applies, "Successful recovery must clear uncertainty");
    }

    @Test
    void retainsRecoveryAcrossPreparationFailureAndFurtherFailedTransitionsWithoutSecrets() throws Exception {
        var backend = new ScopedBackend("db");
        var core = new PactCore(List.of(backend));
        var a = state(access("db", "a"));
        var b = new PactState(Set.of(access("db", "b")), Set.of(new Identity("db", "alice", true,
                "credentials/password", "2", new SecretValue("must-not-be-retained"))));
        core.apply(a);
        backend.failApply = backend.failRollback = true;
        assertThrows(RollbackException.class, () -> core.apply(b));
        backend.failPrepare = true;
        assertThrows(ApplyException.class, () -> core.apply(a));
        backend.failPrepare = false;
        backend.failApply = backend.failRollback = true;
        var c = state(access("db", "c"));
        assertThrows(RollbackException.class, () -> core.apply(c));
        core.apply(a);
        assertEquals(Set.of(a, b.withoutSecrets(), c), backend.lastRecovery);
        backend.lastRecovery.stream().flatMap(value -> value.identities().stream())
                .forEach(identity -> assertNull(identity.password()));
        assertEquals(a.accesses(), backend.actual);
    }

    @Test
    void unsupportedRecoveryFailsExplicitlyInsteadOfSilentlyDiscardingCleanupScope() {
        Backend backend = new Backend() {
            public String id() { return "legacy"; }
            public void apply(PactState state) { fail("Recovery must not silently use ordinary apply"); }
        };
        assertThrows(BackendFeatureNotImplementedException.class, () -> backend.prepareTransaction(
                PactState.empty(), PactState.empty(), Set.of(PactState.empty())));
    }

    private static PactState state(Access... accesses) { return new PactState(Set.of(accesses)); }
    private static Access access(String backend, String database) {
        return new Access("alice", new Resource(backend, Map.of("database", database)), Map.of());
    }

    private static final class ScopedBackend implements Backend {
        final String id;
        final Set<Access> actual = new HashSet<>();
        Set<PactState> lastRecovery = Set.of();
        boolean failApply, failRollback, failPrepare;
        int applies;
        ScopedBackend(String id) { this.id = id; }
        public String id() { return id; }
        public void validateIdentities(Set<Identity> identities) { }
        public void apply(PactState state) { fail("Prepared transactions must handle application"); }
        public BackendTransaction prepareTransaction(PactState previous, PactState desired, Set<PactState> recovery)
                throws BackendException {
            if (failPrepare) throw new BackendOperationException("Preparation failed");
            lastRecovery = Set.copyOf(recovery);
            var scope = new HashSet<Resource>();
            var states = new HashSet<>(recovery);
            states.add(previous);
            states.add(desired);
            states.forEach(value -> value.accesses().forEach(access -> scope.add(access.resource())));
            return new BackendTransaction() {
                private void reconcile(PactState target) {
                    actual.removeIf(access -> scope.contains(access.resource()));
                    actual.addAll(target.accesses());
                }
                public void apply() throws BackendException {
                    applies++;
                    reconcile(desired);
                    if (failApply) { failApply = false; throw new BackendOperationException("Lost response"); }
                }
                public void rollback() throws BackendException {
                    if (failRollback) { failRollback = false; throw new BackendOperationException("Rollback unavailable"); }
                    reconcile(previous);
                }
            };
        }
    }
}
