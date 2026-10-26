package io.github.pactproject.core;

import io.github.pactproject.api.Access;
import io.github.pactproject.api.Backend;
import io.github.pactproject.api.BackendTransaction;
import io.github.pactproject.api.Identity;
import io.github.pactproject.api.PactState;
import io.github.pactproject.api.exception.BackendException;
import io.github.pactproject.core.exception.ApplyException;
import io.github.pactproject.core.exception.BackendNotFoundException;
import io.github.pactproject.core.exception.CoreException;
import io.github.pactproject.core.exception.RollbackException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class PactCore implements AutoCloseable {

    private static final Logger log =
            LoggerFactory.getLogger(PactCore.class);

    private final Map<String, Backend> backends;

    private PactState appliedState = PactState.empty();
    // Runtime only: preserve every possibly affected scope until apply or rollback succeeds.
    private final Map<String, Set<PactState>> recoveryStates = new HashMap<>();
    private boolean reconciliationStarted;
    private boolean closed;

    public PactCore(Collection<? extends Backend> backends) {
        this.backends = backends.stream()
                .collect(Collectors.toUnmodifiableMap(
                        Backend::id,
                        Function.identity()
                ));

        log.debug(
                "Initialized PactCore with backends: {}",
                this.backends.keySet()
        );
    }

    public synchronized void apply(PactState desiredState)
            throws CoreException {
        if (closed) throw new IllegalStateException("PACT core is closed");

        String reconciliationId = UUID.randomUUID().toString();
        long started = System.nanoTime();
        log.info("reconciliation={} phase=reconcile result=started desired_accesses={} desired_identities={}",
                reconciliationId, desiredState.accesses().size(), desiredState.identities().size());
        try {
            applyDesiredState(desiredState, reconciliationId);
            log.info("reconciliation={} phase=reconcile result=completed duration_ms={}", reconciliationId, elapsedMillis(started));
        } catch (CoreException | RuntimeException e) {
            log.error("reconciliation={} phase=reconcile result=failed duration_ms={} recovery_backends={} error_type={}",
                    reconciliationId, elapsedMillis(started), new TreeSet<>(recoveryStates.keySet()), e.getClass().getSimpleName());
            throw e;
        }
    }

    private void applyDesiredState(PactState desiredState, String reconciliationId) throws CoreException {

        reconciliationStarted = true;
        log.debug(
                "Applying desired state with {} accesses",
                desiredState.accesses().size()
        );

        validateBackends(desiredState);

        var previousByBackend = groupByBackend(appliedState);
        var desiredByBackend = groupByBackend(desiredState);

        var backendIds = new TreeSet<String>();
        backendIds.addAll(previousByBackend.keySet());
        backendIds.addAll(desiredByBackend.keySet());
        backendIds.addAll(recoveryStates.keySet());

        log.debug(
                "Reconciling backends: {}",
                backendIds
        );

        var transactions =
                new LinkedHashMap<String, BackendTransaction>();

        for (var backendId : backendIds) {
            var previousState = previousByBackend
                    .getOrDefault(
                            backendId,
                            PactState.empty()
                    );

            var desiredStateForBackend = desiredByBackend
                    .getOrDefault(
                            backendId,
                            PactState.empty()
                    );

            try {
                backend(backendId).validateIdentities(desiredStateForBackend.identities());
            } catch (BackendException e) {
                throw new ApplyException("Failed to validate backend identities", e);
            }

            if (previousState.equals(desiredStateForBackend) && !recoveryStates.containsKey(backendId)) {
                log.debug(
                        "reconciliation={} backend={} phase=skip result=unchanged",
                        reconciliationId, backendId
                );
                continue;
            }

            try {
                log.debug("reconciliation={} backend={} phase=prepare previous_accesses={} desired_accesses={} recovery_states={}",
                        reconciliationId, backendId, previousState.accesses().size(), desiredStateForBackend.accesses().size(),
                        recoveryStates.getOrDefault(backendId, Set.of()).size());
                transactions.put(
                        backendId,
                        phase(reconciliationId, backendId, "prepare", () -> backend(backendId).prepareTransaction(
                                previousState,
                                desiredStateForBackend,
                                recoveryStates.getOrDefault(backendId, Set.of())
                        ))
                );
            } catch (BackendException e) {
                throw new ApplyException("Failed to prepare desired state", e);
            }
        }

        var startedTransactions =
                new LinkedHashMap<String, BackendTransaction>();

        for (var entry : transactions.entrySet()) {
            var backendId = entry.getKey();
            log.info(
                    "Applying state to backend '{}' ({} -> {} accesses)",
                    backendId,
                    previousByBackend
                            .getOrDefault(backendId, PactState.empty())
                            .accesses()
                            .size(),
                    desiredByBackend
                            .getOrDefault(backendId, PactState.empty())
                            .accesses()
                            .size()
            );

            try {
                // Include the failing apply: it may already have written remotely.
                var possibleStates = new HashSet<>(recoveryStates.getOrDefault(backendId, Set.of()));
                possibleStates.add(previousByBackend.getOrDefault(backendId, PactState.empty()).withoutSecrets());
                possibleStates.add(desiredByBackend.getOrDefault(backendId, PactState.empty()).withoutSecrets());
                recoveryStates.put(backendId, Set.copyOf(possibleStates));
                startedTransactions.put(backendId, entry.getValue());
                phase(reconciliationId, backendId, "apply", () -> { entry.getValue().apply(); return null; });
            } catch (BackendException e) {
                log.warn(
                        "Failed to apply state to backend '{}', rolling back",
                        backendId,
                        e
                );
                rollback(startedTransactions, e, reconciliationId);
                throw new ApplyException("Failed to apply desired state", e);
            }
        }

        appliedState = desiredState.withoutSecrets();
        startedTransactions.keySet().forEach(recoveryStates::remove);

        log.info(
                "Successfully applied desired state to all backends"
        );
    }

    public synchronized void restoreAppliedState(PactState restoredState)
            throws CoreException {
        if (closed) throw new IllegalStateException("PACT core is closed");
        if (reconciliationStarted) {
            throw new IllegalStateException(
                    "Applied state can only be restored before reconciliation"
            );
        }

        validateBackends(restoredState);
        appliedState = restoredState.withoutSecrets();
        log.info(
                "Restored applied state with {} access(es) and {} identity/identities",
                appliedState.accesses().size(),
                appliedState.identities().size()
        );
    }

    @Override
    public synchronized void close() throws CoreException {
        if (closed) return;
        closed = true;
        CoreException failure = null;
        for (var entry : new TreeMap<>(backends).descendingMap().entrySet()) {
            try { entry.getValue().close(); }
            catch (Exception e) {
                if (failure == null) failure = new CoreException("Failed to close PACT backend clients");
                failure.addSuppressed(e);
            }
        }
        if (failure != null) throw failure;
    }

    private void validateBackends(PactState desiredState)
            throws BackendNotFoundException {

        for (var access : desiredState.accesses()) {
            var backendId = access.resource().backendId();

            if (!backends.containsKey(backendId)) {
                log.error(
                        "Unknown backend '{}' referenced by desired state",
                        backendId
                );

                throw new BackendNotFoundException(backendId);
            }
        }
        for (var identity : desiredState.identities()) {
            var backendId = identity.backendId();
            if (!backends.containsKey(backendId)) {
                log.error(
                        "Unknown backend '{}' referenced by identity",
                        backendId
                );
                throw new BackendNotFoundException(backendId);
            }
        }
    }

    private void rollback(
            Map<String, BackendTransaction> transactions,
            BackendException applyException,
            String reconciliationId
    ) throws RollbackException {

        if (transactions.isEmpty()) {
            log.debug(
                    "Nothing to rollback"
            );
            return;
        }

        log.info(
                "Rolling back {} backend(s): {}",
                transactions.size(),
                transactions.keySet()
        );

        RollbackException rollbackException = null;

        var entries = new ArrayList<>(transactions.entrySet());
        for (var i = entries.size() - 1; i >= 0; i--) {
            var entry = entries.get(i);
            var backendId = entry.getKey();

            log.info(
                    "Rolling back backend '{}'",
                    backendId
            );

            try {
                phase(reconciliationId, backendId, "rollback", () -> { entry.getValue().rollback(); return null; });
                recoveryStates.remove(backendId);

                log.info(
                        "Successfully rolled back backend '{}'",
                        backendId
                );

            } catch (BackendException e) {
                log.error(
                        "Failed to rollback backend '{}': {}",
                        backendId,
                        e.getMessage(),
                        e
                );

                if (rollbackException == null) {
                    rollbackException = new RollbackException(
                            "Failed to rollback desired state",
                            e
                    );
                } else {
                    rollbackException.addSuppressed(e);
                }
            }
        }

        if (rollbackException != null) {
            rollbackException.addSuppressed(applyException);

            log.error(
                    "Rollback failed; system may be in a partially applied state"
            );

            throw rollbackException;
        }

        log.info(
                "Rollback completed successfully"
        );
    }

    private Backend backend(String id) {
        return backends.get(id);
    }

    private <T> T phase(String reconciliationId, String backendId, String phase, BackendCall<T> operation)
            throws BackendException {
        long started = System.nanoTime();
        log.info("reconciliation={} backend={} phase={} result=started", reconciliationId, backendId, phase);
        try {
            T result = operation.call();
            log.info("reconciliation={} backend={} phase={} result=completed duration_ms={}",
                    reconciliationId, backendId, phase, elapsedMillis(started));
            return result;
        } catch (BackendException | RuntimeException e) {
            log.error("reconciliation={} backend={} phase={} result=failed duration_ms={} error_type={}",
                    reconciliationId, backendId, phase, elapsedMillis(started), e.getClass().getSimpleName());
            throw e;
        }
    }

    private static long elapsedMillis(long started) { return (System.nanoTime() - started) / 1_000_000; }
    @FunctionalInterface private interface BackendCall<T> { T call() throws BackendException; }

    private Map<String, PactState> groupByBackend(
            PactState state
    ) {
        var groupedAccesses = new LinkedHashMap<String, Set<Access>>();
        var groupedIdentities = new LinkedHashMap<String, Set<Identity>>();

        for (var access : state.accesses()) {
            var backendId = access.resource().backendId();

            groupedAccesses.computeIfAbsent(
                    backendId,
                    _ -> new HashSet<>()
            ).add(access);
        }

        for (var identity : state.identities()) {
            groupedIdentities.computeIfAbsent(
                    identity.backendId(),
                    _ -> new HashSet<>()
            ).add(identity);
        }

        var backendIds = new HashSet<String>();
        backendIds.addAll(groupedAccesses.keySet());
        backendIds.addAll(groupedIdentities.keySet());
        var result = new LinkedHashMap<String, PactState>();
        for (var backendId : backendIds) {
            result.put(
                    backendId,
                    new PactState(
                            groupedAccesses.getOrDefault(backendId, Set.of()),
                            groupedIdentities.getOrDefault(backendId, Set.of())
                    )
            );
        }
        return Map.copyOf(result);
    }
}
