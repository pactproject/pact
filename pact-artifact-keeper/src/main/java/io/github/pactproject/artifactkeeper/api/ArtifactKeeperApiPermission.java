package io.github.pactproject.artifactkeeper.api;

import java.util.Set;

public record ArtifactKeeperApiPermission(
        String id,
        String principalType,
        String principalId,
        String targetType,
        String targetId,
        Set<String> actions
) {
    public ArtifactKeeperApiPermission {
        actions = Set.copyOf(actions);
    }
}
