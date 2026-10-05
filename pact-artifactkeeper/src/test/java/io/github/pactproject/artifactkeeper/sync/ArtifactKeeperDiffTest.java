package io.github.pactproject.artifactkeeper.sync;

import io.github.pactproject.artifactkeeper.model.ActualArtifactKeeperPermission;
import io.github.pactproject.artifactkeeper.model.ArtifactKeeperPermission;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ArtifactKeeperDiffTest {

    @Test
    void returnsEmptyDiffWhenActualEqualsDesired() {
        ArtifactKeeperPermission desired =
                permission("alice", "read");

        ActualArtifactKeeperPermission actual =
                actualPermission("alice", "read");

        assertEquals(
                new ArtifactKeeperDiff.PermissionDiff(
                        List.of(),
                        List.of()
                ),
                ArtifactKeeperDiff.diff(
                        List.of(actual),
                        List.of(desired)
                )
        );
    }

    @Test
    void createsMissingPermissions() {
        ArtifactKeeperPermission desired =
                permission("alice", "read");

        assertEquals(
                new ArtifactKeeperDiff.PermissionDiff(
                        List.of(desired),
                        List.of()
                ),
                ArtifactKeeperDiff.diff(
                        List.of(),
                        List.of(desired)
                )
        );
    }

    @Test
    void deletesPermissionsMissingFromDesiredState() {
        ActualArtifactKeeperPermission actual =
                actualPermission("alice", "read");

        assertEquals(
                new ArtifactKeeperDiff.PermissionDiff(
                        List.of(),
                        List.of(actual)
                ),
                ArtifactKeeperDiff.diff(
                        List.of(actual),
                        List.of()
                )
        );
    }

    @Test
    void replacesPermissionsWhenActionsDiffer() {
        ActualArtifactKeeperPermission actual =
                actualPermission("alice", "read");

        ArtifactKeeperPermission desired =
                permission("alice", "read", "write");

        assertEquals(
                new ArtifactKeeperDiff.PermissionDiff(
                        List.of(desired),
                        List.of(actual)
                ),
                ArtifactKeeperDiff.diff(
                        List.of(actual),
                        List.of(desired)
                )
        );
    }

    @Test
    void ignoresActionOrdering() {
        ActualArtifactKeeperPermission actual =
                actualPermission("alice", "read", "write");

        ArtifactKeeperPermission desired =
                permission("alice", "write", "read");

        assertEquals(
                new ArtifactKeeperDiff.PermissionDiff(
                        List.of(),
                        List.of()
                ),
                ArtifactKeeperDiff.diff(
                        List.of(actual),
                        List.of(desired)
                )
        );
    }

    @Test
    void deduplicatesDuplicateDesiredPermissions() {
        ArtifactKeeperPermission permission =
                permission("alice", "read");

        assertEquals(
                new ArtifactKeeperDiff.PermissionDiff(
                        List.of(permission),
                        List.of()
                ),
                ArtifactKeeperDiff.diff(
                        List.of(),
                        List.of(
                                permission,
                                permission("alice", "read")
                        )
                )
        );
    }

    @Test
    void mergesDuplicateDesiredPermissions() {
        ArtifactKeeperPermission read =
                permission("alice", "read");

        ArtifactKeeperPermission write =
                permission("alice", "write");

        ArtifactKeeperPermission expected =
                permission("alice", "read", "write");

        assertEquals(
                new ArtifactKeeperDiff.PermissionDiff(
                        List.of(expected),
                        List.of()
                ),
                ArtifactKeeperDiff.diff(
                        List.of(),
                        List.of(read, write)
                )
        );
    }

    @Test
    void mergesDuplicateActualPermissions() {
        ActualArtifactKeeperPermission read =
                actualPermission("alice", "read");

        ActualArtifactKeeperPermission write =
                actualPermission("alice", "write");

        ArtifactKeeperPermission expected =
                permission("alice", "read", "write");

        assertEquals(
                new ArtifactKeeperDiff.PermissionDiff(
                        List.of(),
                        List.of()
                ),
                ArtifactKeeperDiff.diff(
                        List.of(read, write),
                        List.of(expected)
                )
        );
    }

    private static ArtifactKeeperPermission permission(
            String username,
            String... actions
    ) {
        return new ArtifactKeeperPermission(
                "repository-a",
                username.startsWith("svc-") ? "service_account" : "user",
                username,
                Set.of(actions)
        );
    }

    private static ActualArtifactKeeperPermission actualPermission(
            String username,
            String... actions
    ) {
        return new ActualArtifactKeeperPermission(
                "permission-1",
                "repository-a",
                username.startsWith("svc-") ? "service_account" : "user",
                username,
                Set.of(actions)
        );
    }

    private static ActualArtifactKeeperPermission expectedWithoutId(
            ActualArtifactKeeperPermission permission
    ) {
        return permission;
    }
}
