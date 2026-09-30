package io.github.pactproject.app;

import io.github.pactproject.api.PactState;
import io.github.pactproject.api.StateProvider;
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

public final class PactApplication
        implements AutoCloseable
{
    private static final Logger log =
            LoggerFactory.getLogger(PactApplication.class);

    private final PactCore core;
    private final StateProvider stateProvider;
    private final PluginSet plugins;

    private PactApplication(
            PactCore core,
            StateProvider stateProvider,
            PluginSet plugins)
    {
        this.core = core;
        this.stateProvider = stateProvider;
        this.plugins = plugins;
    }

    public static PactApplication create(
            Path configPath,
            Path pluginsDirectory)
            throws PactApplicationException
    {
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

            try {
                PluginRegistry registry =
                        new PluginRegistry(plugins);

                PactComponents components =
                        new PactComponentsFactory(
                                registry
                        ).create(config);

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

    public void run()
            throws PactApplicationException
    {
        try {
            log.info("Loading desired PACT state");

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
        catch (CoreException e) {
            throw new PactApplicationException(
                    "PACT reconciliation failed",
                    e
            );
        }
        catch (Exception e) {
            throw new PactApplicationException(
                    "Failed to load desired PACT state",
                    e
            );
        }
    }

    @Override
    public void close()
            throws IOException
    {
        plugins.close();
    }
}
