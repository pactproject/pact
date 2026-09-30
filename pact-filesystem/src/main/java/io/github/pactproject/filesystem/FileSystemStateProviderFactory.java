package io.github.pactproject.filesystem;

import io.github.pactproject.api.StateProvider;
import io.github.pactproject.api.StateProviderFactory;

import java.nio.file.Path;
import java.util.Map;

public final class FileSystemStateProviderFactory
        implements StateProviderFactory
{
    @Override
    public String type()
    {
        return "filesystem";
    }

    @Override
    public StateProvider create(
            Map<String, String> config)
    {
        String path = config.get("path");

        if (path == null) {
            throw new IllegalArgumentException(
                    "Missing 'path' configuration for state provider"
            );
        }

        return new FileSystemStateProvider(
                Path.of(path)
        );
    }
}
