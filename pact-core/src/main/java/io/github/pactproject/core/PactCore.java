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

public final class PactCore {

    private static final Logger log =
            LoggerFactory.getLogger(PactCore.class);

    private final Map<String, Backend> backends;

    private PactState appliedState = PactState.empty();
    private boolean reconciliationStarted;

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

            if (previousState.equals(desiredStateForBackend)) {
                log.debug(
                        "Backend '{}' is already up to date",
                        backendId
                );
                continue;
            }

            try {
                transactions.put(
                        backendId,
                        backend(backendId).prepare(
                                previousState,
                                desiredStateForBackend
                        )
                );
            } catch (BackendException e) {
                rollback(transactions, e);
                throw new ApplyException("Failed to prepare desired state", e);
            }
        }

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
                entry.getValue().apply();
            } catch (BackendException e) {
                log.warn(
                        "Failed to apply state to backend '{}', rolling back",
                        backendId,
                        e
                );
                rollback(transactions, e);
                throw new ApplyException("Failed to apply desired state", e);
            }
        }

        appliedState = desiredState.withoutSecrets();

        log.info(
                "Successfully applied desired state to all backends"
        );
    }

    public synchronized void restoreAppliedState(PactState restoredState)
            throws CoreException {
        if (reconciliationStarted) {
            throw new IllegalStateException(
                    "Applied state can only be restored before reconciliation"
            );
        }

        validateBackends(restoredState);
        appliedState = restoredState.withoutSecrets();
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
            BackendException applyException
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
                entry.getValue().rollback();

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
