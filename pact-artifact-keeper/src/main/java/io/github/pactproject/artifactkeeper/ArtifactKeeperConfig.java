package io.github.pactproject.artifactkeeper;

import java.net.URI;
import java.util.Map;

public record ArtifactKeeperConfig(
        URI url,
        String token,
        int perPage,
        ArtifactKeeperApiProfile apiProfile
) {
    public ArtifactKeeperConfig(URI url, String token, int perPage) {
        this(url, token, perPage, ArtifactKeeperApiProfile.CURRENT);
    }

    public static ArtifactKeeperConfig from(
            Map<String, String> config)
    {
        ArtifactKeeperApiProfile apiProfile =
                ArtifactKeeperApiProfile.from(config.getOrDefault("api-profile", "current"));
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
                perPage,
                apiProfile
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
