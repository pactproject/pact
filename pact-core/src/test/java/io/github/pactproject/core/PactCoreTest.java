package io.github.pactproject.core;

import io.github.pactproject.api.Access;
import io.github.pactproject.api.Backend;
import io.github.pactproject.api.PactState;
import io.github.pactproject.api.Resource;
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
    void skipsUnchangedBackend() throws Exception {
        var backend = new TestBackend("test");
        var core = new PactCore(List.of(backend));

        var state = state(
                access("alice", "test", "table", "events")
        );

        core.apply(state);
        core.apply(state);

        assertEquals(1, backend.applyCount());
        assertEquals(state, backend.currentState());
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
        public void apply(PactState state)
                throws BackendOperationException {

            if (failingStates.contains(state)) {
                throw new BackendOperationException(
                        "Apply failed"
                );
            }

            appliedStates.add(state);
            currentState = state;
        }

        void failOn(PactState state) {
            failingStates.add(state);
        }

        void clearFailures() {
            failingStates.clear();
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
