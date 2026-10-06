package io.github.pactproject.artifactkeeper;

import io.github.pactproject.api.Backend;
import io.github.pactproject.api.BackendFactory;
import io.github.pactproject.artifactkeeper.api.ArtifactKeeperClient;
import io.github.pactproject.artifactkeeper.client.ArtifactKeeperHttpClient;

import java.util.Map;

public final class ArtifactKeeperBackendFactory
        implements BackendFactory
{
    @Override
    public String type()
    {
        return "artifact-keeper";
    }

    @Override
    public Backend create(
            String id,
            Map<String, String> config)
    {
        ArtifactKeeperConfig artifactKeeperConfig =
                ArtifactKeeperConfig.from(config);

        ArtifactKeeperClient client =
                new ArtifactKeeperHttpClient(
                        artifactKeeperConfig
                );

        return new ArtifactKeeperBackend(
                id,
                client
        );
    }
}
