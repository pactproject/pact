package io.github.pactproject.app.plugin;

import io.github.pactproject.api.BackendFactory;
import io.github.pactproject.api.StateProviderFactory;

import java.io.IOException;
import java.net.URLClassLoader;
import java.util.List;

public final class PluginSet
        implements AutoCloseable
{
    private final URLClassLoader classLoader;
    private final List<BackendFactory> backendFactories;
    private final List<StateProviderFactory> stateProviderFactories;

    public PluginSet(
            URLClassLoader classLoader,
            List<BackendFactory> backendFactories,
            List<StateProviderFactory> stateProviderFactories)
    {
        this.classLoader = classLoader;
        this.backendFactories =
                List.copyOf(backendFactories);
        this.stateProviderFactories =
                List.copyOf(stateProviderFactories);
    }

    public List<BackendFactory> backendFactories()
    {
        return backendFactories;
    }

    public List<StateProviderFactory> stateProviderFactories()
    {
        return stateProviderFactories;
    }

    @Override
    public void close()
            throws IOException
    {
        classLoader.close();
    }
}
