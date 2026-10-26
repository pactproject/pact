package io.github.pactproject.core;

import io.github.pactproject.api.Access;
import io.github.pactproject.api.Backend;
import io.github.pactproject.api.BackendTransaction;
import io.github.pactproject.api.Identity;
import io.github.pactproject.api.PactState;
import io.github.pactproject.api.exception.BackendException;
import io.github.pactproject.api.Resource;
import io.github.pactproject.api.SecretValue;
import io.github.pactproject.api.exception.BackendOperationException;
import io.github.pactproject.core.exception.ApplyException;
import io.github.pactproject.core.exception.BackendNotFoundException;
import io.github.pactproject.core.exception.RollbackException;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PactCoreTest {

    @Test
    void rejectsUnimplementedIdentitiesBeforeAnyBackendApplies() {
        var operations = new ArrayList<String>();
        var core = new PactCore(List.of(transactionBackend("a-first", operations),
                transactionBackend("z-unsupported", operations)));
        var desired = new PactState(Set.of(access("alice", "a-first", "table", "events")),
                Set.of(new Identity("z-unsupported", "alice", true, null, null, null)));

        var failure = assertThrows(ApplyException.class, () -> core.apply(desired));
        assertTrue(failure.getCause() instanceof io.github.pactproject.api.exception.BackendFeatureNotImplementedException);
        assertEquals(List.of("prepare:a-first"), operations);
    }

    @Test
    void closesEveryBackendOnceEvenIfOneFailsAndRejectsFurtherWork() throws Exception {
        var closed = new ArrayList<String>();
        Backend a = new Backend() {
            public String id() { return "a"; }
            public void apply(PactState state) { }
            public void close() { closed.add("a"); }
        };
        Backend b = new Backend() {
            public String id() { return "b"; }
            public void apply(PactState state) { }
            public void close() throws BackendException {
                closed.add("b");
                throw new BackendOperationException("Deliberate close failure");
            }
        };
        var core = new PactCore(List.of(a, b));
        assertThrows(io.github.pactproject.core.exception.CoreException.class, core::close);
        assertEquals(List.of("b", "a"), closed);
        core.close();
        assertEquals(2, closed.size());
        assertThrows(IllegalStateException.class, () -> core.apply(PactState.empty()));
        assertThrows(IllegalStateException.class, () -> core.restoreAppliedState(PactState.empty()));
    }

    @Test
    void appliesNewState() throws Exception {
        var backend = new TestBackend("test");
        var core = new PactCore(List.of(backend));

        var state = state(
                access("alice", "test", "table", "events")
        );

        core.apply(state);

        assertEquals(state, backend.currentState());
        assertEquals(1, backend.applyCount());
    }

    @Test
    void skipsUnchangedBackendEvenWhenExternalStateDrifts() throws Exception {
        var backend = new TestBackend("test");
        var core = new PactCore(List.of(backend));

        var state = state(
                access("alice", "test", "table", "events")
        );

        core.apply(state);
        backend.currentState = PactState.empty(); // Simulate a manual external change.
        core.apply(state);

        assertEquals(1, backend.applyCount());
        assertEquals(PactState.empty(), backend.currentState());
    }

    @Test
    void previousStateKeepsSecretVersionButNotPasswordDuringPreparationAndRollback() throws Exception {
        var previousStates = new ArrayList<PactState>();
        var appliedStates = new ArrayList<PactState>();
        Backend backend = new Backend() {
            public String id() { return "postgres"; }
            public void validateIdentities(Set<Identity> identities) { }
            public BackendTransaction prepareTransaction(PactState previous, PactState desired)
                    throws BackendException {
                previousStates.add(previous);
                return Backend.super.prepareTransaction(previous, desired);
            }
            public void apply(PactState state) throws BackendException {
                appliedStates.add(state);
                if (state.identities().iterator().next().passwordVersion().equals("8")) {
                    throw new BackendOperationException("Failed after identity write");
                }
            }
        };
        var core = new PactCore(List.of(backend));
        var previous = new PactState(Set.of(), Set.of(new Identity("postgres", "app", true,
                "app-secret/password", "7", new SecretValue("previous-secret"))));
        var desired = new PactState(Set.of(), Set.of(new Identity("postgres", "app", true,
                "app-secret/password", "8", new SecretValue("next-secret"))));
        core.apply(previous);
        assertThrows(ApplyException.class, () -> core.apply(desired));

        for (PactState retained : List.of(previousStates.get(1), appliedStates.get(2))) {
            var identity = retained.identities().iterator().next();
            assertEquals("app-secret/password", identity.passwordSource());
            assertEquals("7", identity.passwordVersion());
            org.junit.jupiter.api.Assertions.assertNull(identity.password());
        }
        core.apply(previous);
        assertEquals(3, appliedStates.size(), "Failed rotation must not advance the agreed state");
    }

    @Test
    void reconcilesIdentityOnlyBackendAndRedactsPassword() throws Exception {
        var backend = new TestBackend("postgres");
        var core = new PactCore(List.of(backend));
        var state = new PactState(
                Set.of(),
                Set.of(new Identity(
                        "postgres",
                        "app",
                        true,
                        "app-secret/password",
                        "7",
                        new SecretValue("do-not-log")
                ))
        );

        assertTrue(!state.toString().contains("do-not-log"));
        core.apply(state);
        core.apply(state);

        assertEquals(1, backend.applyCount());
    }

    @Test
    void rejectsUnknownBackendReferencedOnlyByIdentity() {
        var core = new PactCore(List.of(new TestBackend("postgres")));
        var state = new PactState(
                Set.of(),
                Set.of(new Identity(
                        "unknown",
                        "app",
                        true,
                        null,
                        null,
                        null
                ))
        );

        assertThrows(
                BackendNotFoundException.class,
                () -> core.apply(state)
        );
    }

    @Test
    void restoresAppliedStateWithoutApplyingItAgain() throws Exception {
        var backend = new TestBackend("test");
        var core = new PactCore(List.of(backend));
        var restoredState = state(
                access("alice", "test", "table", "events")
        );

        core.restoreAppliedState(restoredState);
        core.apply(restoredState);

        assertEquals(0, backend.applyCount());
    }

    @Test
    void preparesChangedBackendsBeforeApplyingAny() throws Exception {
        var operations = new ArrayList<String>();
        var core = new PactCore(
                List.of(
                        transactionBackend("first", operations),
                        transactionBackend("second", operations)
                )
        );

        core.apply(state(
                access("alice", "first", "table", "events"),
                access("alice", "second", "table", "events")
        ));

        assertEquals(
                List.of(
                        "prepare:first",
                        "prepare:second",
                        "apply:first",
                        "apply:second"
                ),
                operations
        );
    }

    @Test
    void doesNotRollBackPreparedTransactionsWhenPreparationFails() {
        var operations = new ArrayList<String>();
        Backend prepared = transactionBackend("first", operations);
        Backend failing = new Backend()
        {
            @Override
            public String id()
            {
                return "second";
            }

            @Override
            public void apply(PactState state)
            {
                throw new AssertionError("apply must not run");
            }

            @Override
            public BackendTransaction prepareTransaction(
                    PactState previousState,
                    PactState desiredState)
                    throws BackendException
            {
                operations.add("prepare:second");
                throw new BackendOperationException("prepare failed");
            }
        };
        var core = new PactCore(List.of(prepared, failing));

        assertThrows(
                ApplyException.class,
                () -> core.apply(state(
                        access("alice", "first", "table", "events"),
                        access("alice", "second", "table", "events")
                ))
        );

        assertEquals(
                List.of(
                        "prepare:first",
                        "prepare:second"
                ),
                operations
        );
    }

    @Test
    void rollsBackOnlyStartedTransactionsInReverseOrderIncludingFailedApply() {
        var operations = new ArrayList<String>();
        var core = new PactCore(List.of(
                transactionBackend("04-unstarted", operations),
                transactionBackend("03-failing", operations, true),
                transactionBackend("02-second", operations),
                transactionBackend("01-first", operations)
        ));

        assertThrows(ApplyException.class, () -> core.apply(state(
                access("alice", "01-first", "table", "events"),
                access("alice", "02-second", "table", "events"),
                access("alice", "03-failing", "table", "events"),
                access("alice", "04-unstarted", "table", "events")
        )));

        assertEquals(List.of(
                "prepare:01-first",
                "prepare:02-second",
                "prepare:03-failing",
                "prepare:04-unstarted",
                "apply:01-first",
                "apply:02-second",
                "apply:03-failing",
                "rollback:03-failing",
                "rollback:02-second",
                "rollback:01-first"
        ), operations);
    }

    @Test
    void defaultRollbackReappliesPreviousStateAfterPartialWriteAndAllowsRetry()
            throws Exception {
        var backend = new TestBackend("test");
        var core = new PactCore(List.of(backend));
        var previous = initialStateFor("test");
        var desired = desiredStateFor("test");
        core.apply(previous);
        backend.failAfterWriting(desired);

        assertThrows(ApplyException.class, () -> core.apply(desired));

        assertEquals(previous, backend.currentState());
        assertEquals(List.of(previous, desired, previous), backend.appliedStates());
        core.apply(previous);
        assertEquals(3, backend.applyCount(), "Failed transitions must not advance applied state");

        backend.clearFailures();
        core.apply(desired);
        assertEquals(desired, backend.currentState());
        assertEquals(4, backend.applyCount());
    }

    @Test
    void doesNotApplyWhenAccessOrderChanges() throws Exception {
        var backend = new TestBackend("test");
        var core = new PactCore(List.of(backend));

        var firstState = state(
                access("alice", "test", "table", "events"),
                access("bob", "test", "table", "users")
        );

        var secondState = state(
                access("bob", "test", "table", "users"),
                access("alice", "test", "table", "events")
        );

        core.apply(firstState);
        core.apply(secondState);

        assertEquals(1, backend.applyCount());
        assertEquals(firstState, backend.currentState());
    }

    @Test
    void appliesOnlyChangedBackends() throws Exception {
        var ranger = new TestBackend("ranger");
        var postgres = new TestBackend("postgres");

        var core = new PactCore(List.of(ranger, postgres));

        var initialState = state(
                access("alice", "ranger", "table", "events"),
                access("bob", "postgres", "table", "events")
        );

        core.apply(initialState);

        var desiredState = state(
                access("alice", "ranger", "table", "events"),
                access("charlie", "postgres", "table", "events")
        );

        core.apply(desiredState);

        assertEquals(1, ranger.applyCount());
        assertEquals(2, postgres.applyCount());

        assertEquals(
                state(
                        access("alice", "ranger", "table", "events")
                ),
                ranger.currentState()
        );

        assertEquals(
                state(
                        access("charlie", "postgres", "table", "events")
                ),
                postgres.currentState()
        );
    }

    @Test
    void removesAccess() throws Exception {
        var backend = new TestBackend("test");
        var core = new PactCore(List.of(backend));

        var initialState = state(
                access("alice", "test", "table", "events")
        );

        core.apply(initialState);
        core.apply(PactState.empty());

        assertEquals(PactState.empty(), backend.currentState());
        assertEquals(2, backend.applyCount());
    }

    @Test
    void rejectsUnknownBackend() {
        var backend = new TestBackend("test");
        var core = new PactCore(List.of(backend));

        var state = state(
                access("alice", "unknown", "table", "events")
        );

        assertThrows(
                BackendNotFoundException.class,
                () -> core.apply(state)
        );

        assertEquals(0, backend.applyCount());
        assertEquals(PactState.empty(), backend.currentState());
    }

    @Test
    void rejectsUnknownBackendWithoutApplyingKnownBackends() {
        var backend = new TestBackend("test");
        var core = new PactCore(List.of(backend));

        var state = state(
                access("alice", "test", "table", "events"),
                access("bob", "unknown", "table", "events")
        );

        assertThrows(
                BackendNotFoundException.class,
                () -> core.apply(state)
        );

        assertEquals(0, backend.applyCount());
        assertEquals(PactState.empty(), backend.currentState());
    }

    @Test
    void groupsAccessesByResourceBackend() throws Exception {
        var first = new TestBackend("first");
        var second = new TestBackend("second");

        var core = new PactCore(List.of(first, second));

        core.apply(state(
                access("alice", "first", "table", "events"),
                access("bob", "second", "table", "events"),
                access("charlie", "first", "table", "users")
        ));

        assertEquals(
                state(
                        access("alice", "first", "table", "events"),
                        access("charlie", "first", "table", "users")
                ),
                first.currentState()
        );

        assertEquals(
                state(
                        access("bob", "second", "table", "events")
                ),
                second.currentState()
        );
    }

    @Test
    void doesNotChangeAppliedStateAfterFailedApply() throws Exception {
        var backend = new TestBackend("test");
        var core = new PactCore(List.of(backend));

        var initialState = state(
                access("alice", "test", "table", "events")
        );

        var desiredState = state(
                access("bob", "test", "table", "events")
        );

        core.apply(initialState);

        backend.failOn(desiredStateFor("test"));

        assertThrows(
                ApplyException.class,
                () -> core.apply(desiredState)
        );

        backend.clearFailures();

        assertEquals(initialState, backend.currentState());
    }

    @Test
    void rollsBackPreviouslyAppliedBackends() throws Exception {
        var first = new TestBackend("first");
        var second = new TestBackend("second");

        var core = new PactCore(List.of(first, second));

        var initialState = state(
                access("alice", "first", "table", "events"),
                access("alice", "second", "table", "events")
        );

        var desiredState = state(
                access("bob", "first", "table", "events"),
                access("bob", "second", "table", "events")
        );

        core.apply(initialState);

        // Backend receives only its own part of the desired state.
        second.failOn(desiredStateFor("second"));

        assertThrows(
                ApplyException.class,
                () -> core.apply(desiredState)
        );

        assertEquals(
                initialStateFor("first"),
                first.currentState()
        );

        assertEquals(
                initialStateFor("second"),
                second.currentState()
        );
    }

    @Test
    void continuesCompensationAfterMultipleRollbackFailuresAndRetainsOriginalFailure() {
        var operations = new ArrayList<String>();
        var core = new PactCore(List.of(transactionBackend("a", operations),
                transactionBackend("b", operations, false, true),
                transactionBackend("c", operations, true, true)));
        var failure = assertThrows(RollbackException.class, () -> core.apply(state(
                access("alice", "a", "table", "events"),
                access("alice", "b", "table", "events"),
                access("alice", "c", "table", "events"))));
        assertEquals(List.of("rollback:c", "rollback:b", "rollback:a"),
                operations.subList(operations.size() - 3, operations.size()));
        assertEquals("Rollback failed: c", failure.getCause().getMessage());
        assertEquals(List.of("Rollback failed: b", "Apply failed"),
                Arrays.stream(failure.getSuppressed()).map(Throwable::getMessage).toList());
    }

    @Test
    void throwsRollbackExceptionWhenRollbackFails() throws Exception {
        var first = new TestBackend("first");
        var second = new TestBackend("second");

        var core = new PactCore(
                List.of(first, second)
        );

        var initialState = state(
                access("alice", "first", "table", "events"),
                access("alice", "second", "table", "events")
        );

        var desiredState = state(
                access("bob", "first", "table", "events"),
                access("bob", "second", "table", "events")
        );

        core.apply(initialState);

        // second fails while applying its own desired state.
        second.failOn(desiredStateFor("second"));

        // first fails while rolling back to its own previous state.
        first.failOn(initialStateFor("first"));

        var exception = assertThrows(
                RollbackException.class,
                () -> core.apply(desiredState)
        );

        // first successfully applied the desired state,
        // but could not roll back to the previous state.
        assertEquals(
                desiredStateFor("first"),
                first.currentState()
        );

        // second never applied the desired state.
        assertEquals(
                initialStateFor("second"),
                second.currentState()
        );

        // The original apply failure is retained as suppressed.
        assertTrue(exception.getSuppressed().length > 0);
    }

    private static PactState state(Access... accesses) {
        return new PactState(Set.of(accesses));
    }

    private static Access access(
            String principal,
            String backendId,
            String targetKey,
            String targetValue
    ) {
        return new Access(
                principal,
                new Resource(
                        backendId,
                        Map.of(targetKey, targetValue)
                ),
                Map.of()
        );
    }

    private static Backend transactionBackend(
            String id,
            List<String> operations)
    {
        return transactionBackend(id, operations, false);
    }

    private static Backend transactionBackend(
            String id,
            List<String> operations,
            boolean failApply)
    {
        return transactionBackend(id, operations, failApply, false);
    }

    private static Backend transactionBackend(
            String id,
            List<String> operations,
            boolean failApply,
            boolean failRollback)
    {
        return new Backend()
        {
            @Override
            public String id()
            {
                return id;
            }

            @Override
            public void apply(PactState state)
            {
                throw new AssertionError(
                        "Prepared transaction should handle apply"
                );
            }

            @Override
            public BackendTransaction prepareTransaction(
                    PactState previousState,
                    PactState desiredState)
            {
                operations.add("prepare:" + id);
                return new BackendTransaction()
                {
                    @Override
                    public void apply() throws BackendException
                    {
                        operations.add("apply:" + id);
                        if (failApply) {
                            throw new BackendOperationException("Apply failed");
                        }
                    }

                    @Override
                    public void rollback() throws BackendException
                    {
                        operations.add("rollback:" + id);
                        if (failRollback) throw new BackendOperationException("Rollback failed: " + id);
                    }
                };
            }
        };
    }

    private static PactState initialStateFor(String backendId) {
        return state(
                access(
                        "alice",
                        backendId,
                        "table",
                        "events"
                )
        );
    }

    private static PactState desiredStateFor(String backendId) {
        return state(
                access(
                        "bob",
                        backendId,
                        "table",
                        "events"
                )
        );
    }

    private static final class TestBackend implements Backend {

        private final String id;

        private final List<PactState> appliedStates =
                new ArrayList<>();

        private final Set<PactState> failingStates =
                new HashSet<>();

        private final Set<PactState> partiallyFailingStates =
                new HashSet<>();

        private PactState currentState =
                PactState.empty();

        private TestBackend(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public void validateIdentities(Set<Identity> identities) { }

        @Override
        public void apply(PactState state)
                throws BackendOperationException {

            if (failingStates.contains(state)) {
                throw new BackendOperationException(
                        "Apply failed"
                );
            }

            appliedStates.add(state);
            currentState = state;
            if (partiallyFailingStates.contains(state)) {
                throw new BackendOperationException("Apply failed after writing");
            }
        }

        void failOn(PactState state) {
            failingStates.add(state);
        }

        void clearFailures() {
            failingStates.clear();
            partiallyFailingStates.clear();
        }

        void failAfterWriting(PactState state) {
            partiallyFailingStates.add(state);
        }

        PactState currentState() {
            return currentState;
        }

        int applyCount() {
            return appliedStates.size();
        }

        List<PactState> appliedStates() {
            return List.copyOf(appliedStates);
        }
    }
}
