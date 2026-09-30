package io.github.pactproject.artifactkeeper.sync;

import io.github.pactproject.artifactkeeper.api.ArtifactKeeperApiPermission;
import io.github.pactproject.artifactkeeper.model.ActualArtifactKeeperPermission;
import io.github.pactproject.artifactkeeper.model.ArtifactKeeperState;

import java.util.List;

public final class ArtifactKeeperResolver {

    private ArtifactKeeperResolver() {
    }

    public static List<ActualArtifactKeeperPermission> resolve(
            ArtifactKeeperState state
    ) {
        return state.permissions().stream()
                .map(permission -> resolve(state, permission))
                .toList();
    }

    private static ActualArtifactKeeperPermission resolve(
            ArtifactKeeperState state,
            ArtifactKeeperApiPermission permission
    ) {
        if (!"user".equals(permission.principalType())) {
            throw new IllegalArgumentException(
                    "Unsupported Artifact Keeper principal type: "
                            + permission.principalType()
            );
        }

        if (!"repository".equals(permission.targetType())) {
            throw new IllegalArgumentException(
                    "Unsupported Artifact Keeper target type: "
                            + permission.targetType()
            );
        }

        var user = state.userById(permission.principalId());
        var repository = state.repositoryById(permission.targetId());

        return new ActualArtifactKeeperPermission(
                permission.id(),
                repository.name(),
                user.username(),
                permission.actions()
        );
    }
}
