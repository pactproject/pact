package io.github.pactproject.artifactkeeper.model;

import java.util.Set;

public record ActualArtifactKeeperPermission(
        String id,
        String repository,
        String principalType,
        String username,
        Set<String> actions
) {
    public ActualArtifactKeeperPermission {
        actions = Set.copyOf(actions);
    }
}
