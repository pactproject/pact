package io.github.pactproject.artifactkeeper.model;

import io.github.pactproject.api.value.Value;
import java.util.Map;
import java.util.Set;

public record ArtifactKeeperPermission(
        String targetType,
        String target,
        boolean targetById,
        String principalType,
        String principal,
        Set<String> actions,
        Map<String, Value> conditions
) {
    public ArtifactKeeperPermission(String repository, String principalType, String username, Set<String> actions) {
        this("repository", repository, false, principalType, username, actions, Map.of());
    }

    public ArtifactKeeperPermission(String targetType, String targetId, String principalType,
                                    String principal, Set<String> actions) {
        this(targetType, targetId, true, principalType, principal, actions, Map.of());
    }

    public String repository() { return target; }
    public String username() { return principal; }

    public ArtifactKeeperPermission {
        actions = Set.copyOf(actions);
        conditions = Map.copyOf(conditions);
    }
}
