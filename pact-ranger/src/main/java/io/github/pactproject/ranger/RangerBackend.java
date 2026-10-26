package io.github.pactproject.ranger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.pactproject.api.Backend;
import io.github.pactproject.api.BackendTransaction;
import io.github.pactproject.api.PactState;
import io.github.pactproject.api.exception.BackendException;
import io.github.pactproject.ranger.api.RangerClient;
import io.github.pactproject.ranger.api.RangerClientException;
import io.github.pactproject.ranger.compile.RangerPolicyCompiler;
import io.github.pactproject.ranger.compile.RangerServiceDefinition;
import io.github.pactproject.ranger.sync.RangerPolicySync;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public final class RangerBackend implements Backend {
    private static final Logger log =
            LoggerFactory.getLogger(RangerBackend.class);

    private final String id;
    private final RangerConfig config;
    private final RangerClient client;
    private final RangerPolicySync sync;

    public RangerBackend(
            String id,
            RangerConfig config,
            RangerClient client
    ) {
        this.id = id;
        this.config = config;
        this.client = client;
        this.sync = new RangerPolicySync(
                client,
                config.serviceName(),
                config.pageSize(),
                config.managedOnly(), id
        );
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public void apply(PactState state) throws BackendException {
        BackendTransaction transaction = prepareTransaction(PactState.empty(), state);
        transaction.apply();
    }

    @Override
    public BackendTransaction prepareTransaction(PactState previous, PactState desired,
            java.util.Set<PactState> recoveryStates) throws BackendException {
        // The configured service (and optional managed filter) is already the full cleanup scope.
        for (var recovery : recoveryStates) {
            validateIdentities(recovery.identities());
            for (var access : recovery.accesses()) {
                if (!id.equals(access.resource().backendId())) {
                    throw new io.github.pactproject.api.exception.ValidationException("Recovery backend does not match Ranger backend '" + id + "'");
                }
            }
        }
        return prepareTransaction(previous, desired);
    }

    @Override
    public BackendTransaction prepareTransaction(
            PactState previousState,
            PactState desiredState
    ) throws BackendException {
        validateIdentities(desiredState.identities());
        try {
            String serviceType = client.getServiceType(config.serviceName());
            JsonNode serviceDefinition =
                    client.getServiceDefinition(serviceType);
            RangerServiceDefinition parsedDefinition =
                    RangerServiceDefinition.parse(
                            serviceDefinition,
                            serviceType
                    );
            List<ObjectNode> desired = RangerPolicyCompiler.compile(
                    desiredState,
                    parsedDefinition,
                    id,
                    serviceType,
                    config.serviceName()
            );
            List<ObjectNode> previous = RangerPolicyCompiler.compile(
                    previousState, parsedDefinition, id, serviceType, config.serviceName());
            List<JsonNode> observedPolicies = sync.getPoliciesInScope();
            log.debug(
                    "Prepared Ranger reconciliation for backend '{}' with {} desired "
                            + "policy/policies and {} actual policy/policies",
                    id,
                    desired.size(),
                    observedPolicies.size()
            );

            return new BackendTransaction() {
                @Override
                public void apply() throws BackendException {
                    log.info(
                            "Applying {} Ranger policy/policies for backend '{}'",
                            desired.size(),
                            id
                    );
                    synchronize(desired);
                    log.info(
                            "Applied Ranger policies for backend '{}'",
                            id
                    );
                }

                @Override
                public void rollback() throws BackendException {
                    log.info(
                            "Restoring {} Ranger policy/policies for backend '{}'",
                            previous.size(),
                            id
                    );
                    synchronize(previous);
                    log.info(
                            "Restored Ranger policies for backend '{}'",
                            id
                    );
                }

                private void synchronize(List<? extends JsonNode> target)
                        throws BackendException {
                    try {
                        sync.synchronize(sync.getPolicies(), target);
                    }
                    catch (RangerClientException
                           | IllegalArgumentException e) {
                        throw new BackendException(
                                "Failed to synchronize Ranger policies for "
                                        + config.serviceName(),
                                e
                        );
                    }
                }
            };
        }
        catch (RangerClientException | IllegalArgumentException e) {
            throw new BackendException(
                    "Failed to prepare Ranger policies for "
                            + config.serviceName(),
                    e
            );
        }
    }
}
