package io.github.pactproject.artifactkeeper;

import java.net.URI;
import java.util.Map;

public record ArtifactKeeperConfig(
        URI url,
        String token,
        int perPage
) {
    public static ArtifactKeeperConfig from(
            Map<String, String> config)
    {
        URI url = URI.create(
                require(config, "url")
        );

        String token = require(config, "token");

        int perPage = Integer.parseInt(
                config.getOrDefault("per-page", "100")
        );

        return new ArtifactKeeperConfig(
                url,
                token,
                perPage
        );
    }

    private static String require(
            Map<String, String> config,
            String key)
    {
        String value = config.get(key);

        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Missing required Artifact Keeper config: "
                            + key
            );
        }

        return value;
    }
}
