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
        if (!java.util.Set.of("user", "service_account", "group", "anonymous").contains(permission.principalType())) {
            throw new IllegalArgumentException(
                    "Unsupported Artifact Keeper principal type: "
                            + permission.principalType()
            );
        }

        String principal = permission.principalId();
        String principalType = permission.principalType();
        if (principalType.equals("user") || principalType.equals("service_account")) {
            var user = state.userById(principal);
            if (principalType.equals("service_account") && !user.serviceAccount()) {
                throw new IllegalArgumentException(
                        "Artifact Keeper service_account permission names a human user: " + user.id());
            }
            principal = user.username();
            principalType = user.principalType();
        } else if (principalType.equals("anonymous") && !ArtifactKeeperCompiler.NIL_ID.equals(principal)) {
            throw new IllegalArgumentException("Anonymous principal must have the nil UUID");
        }
        String target = permission.targetType().equals("repository")
                ? state.repositoryById(permission.targetId()).name() : permission.targetId();

        return new ActualArtifactKeeperPermission(
                permission.id(),
                permission.targetType(), target,
                principalType, principal,
                permission.actions(), permission.conditions()
        );
    }
}
