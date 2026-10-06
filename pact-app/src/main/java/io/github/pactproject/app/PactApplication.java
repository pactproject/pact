package io.github.pactproject.app;

import io.github.pactproject.api.ManagedStateProvider;
import io.github.pactproject.api.StateProvider;
import io.github.pactproject.api.StateReconciler;
import io.github.pactproject.api.exception.StateReconciliationException;
import io.github.pactproject.app.config.PactConfig;
import io.github.pactproject.app.config.PactConfigException;
import io.github.pactproject.app.config.PactConfigLoader;
import io.github.pactproject.app.config.PactConfigValidator;
import io.github.pactproject.app.plugin.PluginLoader;
import io.github.pactproject.app.plugin.PluginRegistry;
import io.github.pactproject.app.plugin.PluginSet;
import io.github.pactproject.core.PactCore;
import io.github.pactproject.core.exception.CoreException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;

public final class PactApplication
        implements AutoCloseable
{
    private static final Logger log =
            LoggerFactory.getLogger(PactApplication.class);

    private final PluginSet plugins;
    private final ReconciliationQueue reconciliationQueue;
    private final ManagedStateProvider managedStateProvider;
    private final PactCore core;
    private final CountDownLatch shutdown = new CountDownLatch(1);
    private boolean closed;

    private PactApplication(
            PactCore core,
            StateProvider stateProvider,
            PluginSet plugins)
    {
        this.plugins = plugins;
        this.core = core;
        if (stateProvider instanceof ManagedStateProvider managed) {
            this.managedStateProvider = managed;
            this.reconciliationQueue = null;
        }
        else {
            this.managedStateProvider = null;
            this.reconciliationQueue = new ReconciliationQueue(
                    stateProvider,
                    core
            );
        }
    }

    public static PactApplication create(
            Path configPath,
            Path pluginsDirectory)
            throws PactApplicationException
    {
        log.info(
                "Initializing PACT from config '{}' with plugins in '{}'",
                configPath,
                pluginsDirectory
        );
        try {
            PactConfigLoader configLoader =
                    new PactConfigLoader();

            PactConfig config =
                    configLoader.load(configPath);

            new PactConfigValidator().validate(config);

            PluginSet plugins =
                    new PluginLoader().load(
                            pluginsDirectory
                    );

            PactComponents components = null;
            try {
                PluginRegistry registry =
                        new PluginRegistry(plugins);

                components =
                        new PactComponentsFactory(
                                registry
                        ).create(config);

                log.info(
                        "Initialized PACT with state provider '{}' and {} backend(s)",
                        config.stateProvider().type(),
                        config.backends().size()
                );
                PactCore core =
                        new PactCore(
                                components.backends()
                        );

                return new PactApplication(
                        core,
                        components.stateProvider(),
                        plugins
                );
            }
            catch (Exception e) {
                closeManagedStateProvider(components, e);
                try {
                    plugins.close();
                }
                catch (IOException closeException) {
                    e.addSuppressed(closeException);
                }

                throw e;
            }
        }
        catch (PactConfigException e) {
            throw new PactApplicationException(
                    "Failed to load PACT configuration",
                    e
            );
        }
        catch (Exception e) {
            throw new PactApplicationException(
                    "Failed to initialize PACT application",
                    e
            );
        }
    }

    private static void closeManagedStateProvider(
            PactComponents components,
            Exception failure)
    {
        if (components != null
                && components.stateProvider()
                instanceof ManagedStateProvider managed) {
            try {
                managed.close();
            }
            catch (RuntimeException closeException) {
                failure.addSuppressed(closeException);
            }
        }
    }

    public void run()
            throws PactApplicationException
    {
        if (managedStateProvider != null) {
            log.info("Starting PACT managed state provider");
            runManagedController();
            return;
        }

        log.info("Starting PACT one-shot reconciliation");
        try {
            reconciliationQueue
                    .submit()
                    .join();
        }
        catch (CompletionException e) {
            throw new PactApplicationException(
                    "PACT reconciliation failed",
                    e.getCause()
            );
        }
    }

    private void runManagedController()
            throws PactApplicationException
    {
        try {
            managedStateProvider.start(
                    new StateReconciler() {
                        @Override
                        public void restoreAppliedState(
                                io.github.pactproject.api.PactState state)
                                throws StateReconciliationException
                        {
                            try {
                                core.restoreAppliedState(state);
                            }
                            catch (CoreException e) {
                                throw new StateReconciliationException(
                                        "Failed to restore applied state",
                                        e
                                );
                            }
                        }

                        @Override
                        public void apply(
                                io.github.pactproject.api.PactState state)
                                throws StateReconciliationException
                        {
                            try {
                                core.apply(state);
                            }
                            catch (CoreException e) {
                                throw new StateReconciliationException(
                                        "Failed to reconcile desired state",
                                        e
                                );
                            }
                        }
                    }
            );
            shutdown.await();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PactApplicationException(
                    "PACT controller was interrupted",
                    e
            );
        }
        catch (Exception e) {
            throw new PactApplicationException(
                    "PACT Kubernetes controller failed to start",
                    e
            );
        }
    }

    @Override
    public synchronized void close()
            throws IOException
    {
        if (closed) {
            return;
        }
        closed = true;
        log.info("Shutting down PACT");
        RuntimeException closeFailure = null;
        try {
            if (managedStateProvider != null) {
                managedStateProvider.close();
            }
            else {
                reconciliationQueue.close();
            }
        }
        catch (RuntimeException e) {
            closeFailure = e;
        }
        finally {
            shutdown.countDown();
        }
        try {
            plugins.close();
        }
        catch (IOException e) {
            if (closeFailure != null) {
                closeFailure.addSuppressed(e);
            }
            else {
                throw e;
            }
        }
        if (closeFailure != null) {
            throw closeFailure;
        }
        log.info("PACT shutdown complete");
    }
}
