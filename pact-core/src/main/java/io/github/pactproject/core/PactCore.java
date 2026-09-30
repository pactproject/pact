package io.github.pactproject.core;

import io.github.pactproject.api.Access;
import io.github.pactproject.api.Backend;
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

    public void apply(PactState desiredState)
            throws CoreException {

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

        var affectedBackends = new ArrayList<String>();

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

            log.info(
                    "Applying state to backend '{}' ({} -> {} accesses)",
                    backendId,
                    previousState.accesses().size(),
                    desiredStateForBackend.accesses().size()
            );

            try {
                affectedBackends.add(backendId);

                backend(backendId).apply(
                        desiredStateForBackend
                );

                log.info(
                        "Successfully applied state to backend '{}'",
                        backendId
                );

            } catch (BackendException e) {
                log.warn(
                        "Failed to apply state to backend '{}', rolling back",
                        backendId,
                        e
                );

                rollback(
                        previousByBackend,
                        affectedBackends,
                        e
                );

                throw new ApplyException(
                        "Failed to apply desired state",
                        e
                );
            }
        }

        appliedState = desiredState;

        log.info(
                "Successfully applied desired state to all backends"
        );
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
    }

    private void rollback(
            Map<String, PactState> previousStateByBackend,
            List<String> affectedBackends,
            BackendException applyException
    ) throws RollbackException {

        if (affectedBackends.isEmpty()) {
            log.debug(
                    "Nothing to rollback"
            );
            return;
        }

        log.info(
                "Rolling back {} backend(s): {}",
                affectedBackends.size(),
                affectedBackends
        );

        RollbackException rollbackException = null;

        for (var i = affectedBackends.size() - 1; i >= 0; i--) {
            var backendId = affectedBackends.get(i);

            var previousState = previousStateByBackend.getOrDefault(
                    backendId,
                    PactState.empty()
            );

            log.info(
                    "Rolling back backend '{}' to {} access(es)",
                    backendId,
                    previousState.accesses().size()
            );

            try {
                backend(backendId).apply(previousState);

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
        var grouped = new LinkedHashMap<String, Set<Access>>();

        for (var access : state.accesses()) {
            var backendId = access.resource().backendId();

            grouped.computeIfAbsent(
                    backendId,
                    _ -> new HashSet<>()
            ).add(access);
        }

        return grouped.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        e -> new PactState(e.getValue())
                ));
    }
}
