package io.github.pactproject.artifactkeeper.api;

import io.github.pactproject.api.value.Value;
import java.util.Map;
import java.util.Set;

public record ArtifactKeeperApiPermission(
        String id,
        String principalType,
        String principalId,
        String targetType,
        String targetId,
        Set<String> actions,
        Map<String, Value> conditions
) {
    public ArtifactKeeperApiPermission(String id, String principalType, String principalId,
                                      String targetType, String targetId, Set<String> actions) {
        this(id, principalType, principalId, targetType, targetId, actions, Map.of());
    }

    public ArtifactKeeperApiPermission {
        actions = Set.copyOf(actions);
        conditions = Map.copyOf(conditions);
    }
}
