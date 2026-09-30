package io.github.pactproject.app.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Path;

public final class PactConfigLoader
{
    private final ObjectMapper mapper;

    public PactConfigLoader()
    {
        this.mapper = new ObjectMapper(new YAMLFactory());
    }

    public PactConfig load(Path path)
            throws PactConfigException
    {
        try {
            return mapper.readValue(
                    path.toFile(),
                    PactConfig.class
            );
        }
        catch (IOException | RuntimeException e) {
            throw new PactConfigException(
                    "Failed to load PACT configuration from "
                            + path,
                    e
            );
        }
    }
}
