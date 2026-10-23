package io.github.pactproject.artifactkeeper.model;

import io.github.pactproject.api.value.Value;
import java.util.Map;
import java.util.Set;

public record ActualArtifactKeeperPermission(
        String id,
        String targetType,
        String target,
        String principalType,
        String principal,
        Set<String> actions,
        Map<String, Value> conditions
) {
    public ActualArtifactKeeperPermission(String id, String repository, String principalType,
                                         String username, Set<String> actions) {
        this(id, "repository", repository, principalType, username, actions, Map.of());
    }

    public String repository() { return target; }
    public String username() { return principal; }

    public ActualArtifactKeeperPermission {
        actions = Set.copyOf(actions);
        conditions = Map.copyOf(conditions);
    }
}
