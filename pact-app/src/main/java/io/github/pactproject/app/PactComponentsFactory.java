package io.github.pactproject.app;

import io.github.pactproject.api.Backend;
import io.github.pactproject.api.BackendFactory;
import io.github.pactproject.api.StateProvider;
import io.github.pactproject.api.StateProviderFactory;
import io.github.pactproject.app.config.BackendConfig;
import io.github.pactproject.app.config.PactConfig;
import io.github.pactproject.app.config.StateProviderConfig;
import io.github.pactproject.app.plugin.PluginNotFoundException;
import io.github.pactproject.app.plugin.PluginRegistry;

import java.util.ArrayList;
import java.util.List;

public final class PactComponentsFactory
{
    private final PluginRegistry plugins;

    public PactComponentsFactory(
            PluginRegistry plugins)
    {
        this.plugins = plugins;
    }

    public PactComponents create(PactConfig config)
            throws PactComponentsException
    {
        try {
            StateProvider stateProvider =
                    createStateProvider(
                            config.stateProvider()
                    );

            List<Backend> backends =
                    createBackends(
                            config.backends()
                    );

            return new PactComponents(
                    stateProvider,
                    backends
            );
        }
        catch (PluginNotFoundException e) {
            throw new PactComponentsException(
                    "Failed to create PACT components",
                    e
            );
        }
        catch (RuntimeException e) {
            throw new PactComponentsException(
                    "Failed to create PACT components",
                    e
            );
        }
    }

    private StateProvider createStateProvider(
            StateProviderConfig config)
            throws PluginNotFoundException
    {
        StateProviderFactory factory =
                plugins.stateProviderFactory(
                        config.type()
                );

        return factory.create(
                config.config()
        );
    }

    private List<Backend> createBackends(
            List<BackendConfig> configs)
            throws PluginNotFoundException
    {
        List<Backend> backends =
                new ArrayList<>(configs.size());

        for (BackendConfig config : configs) {
            BackendFactory factory =
                    plugins.backendFactory(
                            config.type()
                    );

            backends.add(
                    factory.create(
                            config.id(),
                            config.config()
                    )
            );
        }

        return List.copyOf(backends);
    }
}
