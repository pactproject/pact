package io.github.pactproject.app.plugin;

import io.github.pactproject.api.BackendFactory;
import io.github.pactproject.api.StateProviderFactory;

import java.util.HashMap;
import java.util.Map;

public final class PluginRegistry
{
    private final Map<String, BackendFactory> backendFactories;
    private final Map<String, StateProviderFactory> stateProviderFactories;

    public PluginRegistry(PluginSet plugins)
            throws PluginLoadException
    {
        backendFactories = indexBackendFactories(
                plugins.backendFactories()
        );

        stateProviderFactories =
                indexStateProviderFactories(
                        plugins.stateProviderFactories()
                );
    }

    public BackendFactory backendFactory(String type)
            throws PluginNotFoundException
    {
        BackendFactory factory =
                backendFactories.get(type);

        if (factory == null) {
            throw new PluginNotFoundException(
                    "No backend plugin found for type: "
                            + type
            );
        }

        return factory;
    }

    public StateProviderFactory stateProviderFactory(
            String type)
            throws PluginNotFoundException
    {
        StateProviderFactory factory =
                stateProviderFactories.get(type);

        if (factory == null) {
            throw new PluginNotFoundException(
                    "No state provider plugin found for type: "
                            + type
            );
        }

        return factory;
    }

    private Map<String, BackendFactory> indexBackendFactories(
            Iterable<BackendFactory> factories)
            throws PluginLoadException
    {
        Map<String, BackendFactory> result =
                new HashMap<>();

        for (BackendFactory factory : factories) {
            String type = factory.type();

            if (result.putIfAbsent(type, factory) != null) {
                throw new PluginLoadException(
                        "Duplicate backend plugin type: "
                                + type,
                        null
                );
            }
        }

        return Map.copyOf(result);
    }

    private Map<String, StateProviderFactory>
    indexStateProviderFactories(
            Iterable<StateProviderFactory> factories)
            throws PluginLoadException
    {
        Map<String, StateProviderFactory> result =
                new HashMap<>();

        for (StateProviderFactory factory : factories) {
            String type = factory.type();

            if (result.putIfAbsent(type, factory) != null) {
                throw new PluginLoadException(
                        "Duplicate state provider plugin type: "
                                + type,
                        null
                );
            }
        }

        return Map.copyOf(result);
    }
}
