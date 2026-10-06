package io.github.pactproject.app.plugin;

import io.github.pactproject.api.BackendFactory;
import io.github.pactproject.api.StateProviderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

public final class PluginLoader
{
    private static final Logger log =
            LoggerFactory.getLogger(PluginLoader.class);

    public PluginSet load(Path pluginsDirectory)
            throws PluginLoadException
    {
        List<URL> pluginUrls;

        try {
            pluginUrls = findPluginJars(
                    pluginsDirectory
            );
        }
        catch (IOException e) {
            throw new PluginLoadException(
                    "Failed to find PACT plugins in "
                            + pluginsDirectory,
                    e
            );
        }

        URLClassLoader classLoader =
                new URLClassLoader(
                        pluginUrls.toArray(URL[]::new),
                        BackendFactory.class.getClassLoader()
                );

        try {
            List<BackendFactory> backendFactories =
                    loadFactories(
                            BackendFactory.class,
                            classLoader
                    );

            List<StateProviderFactory> stateProviderFactories =
                    loadFactories(
                            StateProviderFactory.class,
                            classLoader
                    );

            log.debug(
                    "Loaded {} plugin jar(s), {} backend factory/factories, "
                            + "and {} state provider factory/factories",
                    pluginUrls.size(),
                    backendFactories.size(),
                    stateProviderFactories.size()
            );
            return new PluginSet(
                    classLoader,
                    backendFactories,
                    stateProviderFactories
            );
        }
        catch (RuntimeException e) {
            try {
                classLoader.close();
            }
            catch (IOException closeException) {
                e.addSuppressed(closeException);
            }

            throw new PluginLoadException(
                    "Failed to load PACT plugins from "
                            + pluginsDirectory,
                    e
            );
        }
    }

    private List<URL> findPluginJars(
            Path pluginsDirectory)
            throws IOException
    {
        if (!Files.isDirectory(pluginsDirectory)) {
            throw new IOException(
                    "Plugin directory does not exist: "
                            + pluginsDirectory
            );
        }

        try (var files = Files.list(pluginsDirectory)) {
            return files
                    .filter(path ->
                            path.getFileName()
                                    .toString()
                                    .endsWith(".jar"))
                    .map(this::toUrl)
                    .toList();
        }
    }

    private URL toUrl(Path path)
    {
        try {
            return path.toUri().toURL();
        }
        catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to convert plugin path to URL: "
                            + path,
                    e
            );
        }
    }

    private <T> List<T> loadFactories(
            Class<T> type,
            ClassLoader classLoader)
    {
        List<T> factories = new ArrayList<>();

        ServiceLoader
                .load(type, classLoader)
                .forEach(factories::add);

        return List.copyOf(factories);
    }
}
