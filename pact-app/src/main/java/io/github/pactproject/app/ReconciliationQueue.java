package io.github.pactproject.app;

import io.github.pactproject.api.PactState;
import io.github.pactproject.api.StateProvider;
import io.github.pactproject.core.PactCore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ReconciliationQueue
        implements AutoCloseable
{
    private static final Logger log =
            LoggerFactory.getLogger(ReconciliationQueue.class);

    private final StateProvider stateProvider;
    private final PactCore core;
    private final ExecutorService executor;

    public ReconciliationQueue(
            StateProvider stateProvider,
            PactCore core)
    {
        this.stateProvider = stateProvider;
        this.core = core;
        this.executor = Executors.newSingleThreadExecutor();
    }

    public CompletableFuture<Void> submit()
    {
        return CompletableFuture.runAsync(
                this::reconcile,
                executor
        );
    }

    private void reconcile()
    {
        try {
            log.info("Starting PACT reconciliation");

            PactState desiredState =
                    stateProvider.load();

            log.info(
                    "Loaded desired state with {} accesses",
                    desiredState.accesses().size()
            );

            core.apply(desiredState);

            log.info(
                    "PACT reconciliation completed successfully"
            );
        }
        catch (Exception e) {
            throw new CompletionException(e);
        }
    }

    @Override
    public void close()
    {
        executor.shutdown();
    }
}
