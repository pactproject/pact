package io.github.pactproject.artifactkeeper.model;

import java.util.Set;

public record ArtifactKeeperPermission(
        String repository,
        String username,
        Set<String> actions
) {
    public ArtifactKeeperPermission {
        actions = Set.copyOf(actions);
    }
}
