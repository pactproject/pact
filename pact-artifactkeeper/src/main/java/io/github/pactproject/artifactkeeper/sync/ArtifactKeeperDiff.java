package io.github.pactproject.artifactkeeper.sync;

import io.github.pactproject.artifactkeeper.model.ActualArtifactKeeperPermission;
import io.github.pactproject.artifactkeeper.model.ArtifactKeeperPermission;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ArtifactKeeperDiff {

    private ArtifactKeeperDiff() {
    }

    public static PermissionDiff diff(
            List<ActualArtifactKeeperPermission> actual,
            List<ArtifactKeeperPermission> desired
    ) {
        Map<Key, ActualArtifactKeeperPermission> actualByKey =
                indexActual(actual);

        Map<Key, ArtifactKeeperPermission> desiredByKey =
                indexDesired(desired);

        List<ArtifactKeeperPermission> create = new ArrayList<>();
        List<ActualArtifactKeeperPermission> delete = new ArrayList<>();

        for (Map.Entry<Key, ArtifactKeeperPermission> entry
                : desiredByKey.entrySet()) {

            ActualArtifactKeeperPermission actualPermission =
                    actualByKey.get(entry.getKey());

            if (actualPermission == null) {
                create.add(entry.getValue());
                continue;
            }

            if (!actualPermission.actions()
                    .equals(entry.getValue().actions())) {

                delete.add(actualPermission);
                create.add(entry.getValue());
            }
        }

        for (Map.Entry<Key, ActualArtifactKeeperPermission> entry
                : actualByKey.entrySet()) {

            if (!desiredByKey.containsKey(entry.getKey())) {
                delete.add(entry.getValue());
            }
        }

        return new PermissionDiff(
                List.copyOf(create),
                List.copyOf(delete)
        );
    }

    private static Map<Key, ActualArtifactKeeperPermission> indexActual(
            List<ActualArtifactKeeperPermission> permissions
    ) {
        Map<Key, ActualArtifactKeeperPermission> result = new HashMap<>();

        for (ActualArtifactKeeperPermission permission : permissions) {
            Key key = new Key(
                    permission.repository(),
                    permission.principalType(),
                    permission.username()
            );

            result.merge(
                    key,
                    permission,
                    ArtifactKeeperDiff::mergeActual
            );
        }

        return result;
    }

    private static Map<Key, ArtifactKeeperPermission> indexDesired(
            List<ArtifactKeeperPermission> permissions
    ) {
        Map<Key, ArtifactKeeperPermission> result = new HashMap<>();

        for (ArtifactKeeperPermission permission : permissions) {
            Key key = new Key(
                    permission.repository(),
                    permission.principalType(),
                    permission.username()
            );

            result.merge(
                    key,
                    permission,
                    ArtifactKeeperDiff::mergeDesired
            );
        }

        return result;
    }

    private static ActualArtifactKeeperPermission mergeActual(
            ActualArtifactKeeperPermission first,
            ActualArtifactKeeperPermission second
    ) {
        return new ActualArtifactKeeperPermission(
                first.id(),
                first.repository(),
                first.principalType(),
                first.username(),
                union(first.actions(), second.actions())
        );
    }

    private static ArtifactKeeperPermission mergeDesired(
            ArtifactKeeperPermission first,
            ArtifactKeeperPermission second
    ) {
        return new ArtifactKeeperPermission(
                first.repository(),
                first.principalType(),
                first.username(),
                union(first.actions(), second.actions())
        );
    }

    private static Set<String> union(
            Set<String> first,
            Set<String> second
    ) {
        java.util.HashSet<String> result = new java.util.HashSet<>(first);
        result.addAll(second);
        return Set.copyOf(result);
    }

    private record Key(
            String repository,
            String principalType,
            String username
    ) {
    }

    public record PermissionDiff(
            List<ArtifactKeeperPermission> create,
            List<ActualArtifactKeeperPermission> delete
    ) {
        public PermissionDiff {
            create = List.copyOf(create);
            delete = List.copyOf(delete);
        }
    }
}
