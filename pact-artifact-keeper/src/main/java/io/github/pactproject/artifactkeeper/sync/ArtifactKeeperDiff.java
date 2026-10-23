package io.github.pactproject.artifactkeeper.sync;

import io.github.pactproject.artifactkeeper.model.ActualArtifactKeeperPermission;
import io.github.pactproject.artifactkeeper.model.ArtifactKeeperPermission;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public final class ArtifactKeeperDiff {

    private ArtifactKeeperDiff() {
    }

    public static PermissionDiff diff(
            List<ActualArtifactKeeperPermission> actual,
            List<ArtifactKeeperPermission> desired
    ) {
        Map<Key, List<ActualArtifactKeeperPermission>> actualByKey =
                indexActual(actual);

        Map<Key, ArtifactKeeperPermission> desiredByKey =
                indexDesired(desired);

        List<ArtifactKeeperPermission> create = new ArrayList<>();
        List<ActualArtifactKeeperPermission> delete = new ArrayList<>();

        for (Map.Entry<Key, ArtifactKeeperPermission> entry
                : desiredByKey.entrySet()) {

            if (!actualByKey.containsKey(entry.getKey())) {
                create.add(entry.getValue());
            }
        }

        for (Map.Entry<Key, List<ActualArtifactKeeperPermission>> entry
                : actualByKey.entrySet()) {

            if (!desiredByKey.containsKey(entry.getKey())) {
                delete.addAll(entry.getValue());
            }
        }

        return new PermissionDiff(
                List.copyOf(create),
                List.copyOf(delete)
        );
    }

    private static Map<Key, List<ActualArtifactKeeperPermission>> indexActual(
            List<ActualArtifactKeeperPermission> permissions
    ) {
        Map<Key, List<ActualArtifactKeeperPermission>> result = new HashMap<>();

        for (ActualArtifactKeeperPermission permission : permissions) {
            Key key = new Key(
                    permission.repository(),
                    permission.principalType(),
                    permission.username(),
                    sortedActions(permission.actions())
            );

            // Legacy and current wire types can normalize to the same logical
            // grant. Keep every ID so revocation removes both physical rows.
            result.computeIfAbsent(key, ignored -> new ArrayList<>()).add(permission);
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
                    permission.username(),
                    sortedActions(permission.actions())
            );

            result.merge(
                    key,
                    permission,
                    ArtifactKeeperDiff::mergeDesired
            );
        }

        return result;
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

    private static List<String> sortedActions(Set<String> actions) {
        return List.copyOf(new TreeSet<>(actions));
    }

    private record Key(
            String repository,
            String principalType,
            String username,
            List<String> actions
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
