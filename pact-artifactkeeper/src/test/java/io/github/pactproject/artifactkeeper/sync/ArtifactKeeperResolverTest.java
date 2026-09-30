package io.github.pactproject.artifactkeeper.sync;

import io.github.pactproject.artifactkeeper.api.ArtifactKeeperApiPermission;
import io.github.pactproject.artifactkeeper.api.ArtifactKeeperApiRepository;
import io.github.pactproject.artifactkeeper.api.ArtifactKeeperApiUser;
import io.github.pactproject.artifactkeeper.model.ActualArtifactKeeperPermission;
import io.github.pactproject.artifactkeeper.model.ArtifactKeeperState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ArtifactKeeperResolverTest {

    @Test
    void resolvesPermission() {
        var state = new ArtifactKeeperState(
                List.of(new ArtifactKeeperApiUser("user-1", "alice")),
                List.of(new ArtifactKeeperApiRepository("repo-1", "repository-a")),
                List.of(new ArtifactKeeperApiPermission(
                        "permission-1",
                        "user",
                        "user-1",
                        "repository",
                        "repo-1",
                        Set.of("read", "write")
                ))
        );

        var result = ArtifactKeeperResolver.resolve(state);

        assertEquals(
                List.of(
                        new ActualArtifactKeeperPermission(
                                "permission-1",
                                "repository-a",
                                "alice",
                                Set.of("read", "write")
                        )
                ),
                result
        );
    }

    @Test
    void rejectsUnsupportedPrincipalType() {
        var state = new ArtifactKeeperState(
                List.of(new ArtifactKeeperApiUser("user-1", "alice")),
                List.of(new ArtifactKeeperApiRepository("repo-1", "repository-a")),
                List.of(new ArtifactKeeperApiPermission(
                        "permission-1",
                        "service_account",
                        "user-1",
                        "repository",
                        "repo-1",
                        Set.of("read")
                ))
        );

        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> ArtifactKeeperResolver.resolve(state)
        );

        assertEquals(
                "Unsupported Artifact Keeper principal type: service_account",
                exception.getMessage()
        );
    }

    @Test
    void rejectsUnsupportedTargetType() {
        var state = new ArtifactKeeperState(
                List.of(new ArtifactKeeperApiUser("user-1", "alice")),
                List.of(new ArtifactKeeperApiRepository("repo-1", "repository-a")),
                List.of(new ArtifactKeeperApiPermission(
                        "permission-1",
                        "user",
                        "user-1",
                        "project",
                        "repo-1",
                        Set.of("read")
                ))
        );

        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> ArtifactKeeperResolver.resolve(state)
        );

        assertEquals(
                "Unsupported Artifact Keeper target type: project",
                exception.getMessage()
        );
    }

    @Test
    void rejectsUnknownUser() {
        var state = new ArtifactKeeperState(
                List.of(),
                List.of(new ArtifactKeeperApiRepository("repo-1", "repository-a")),
                List.of(new ArtifactKeeperApiPermission(
                        "permission-1",
                        "user",
                        "missing-user",
                        "repository",
                        "repo-1",
                        Set.of("read")
                ))
        );

        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> ArtifactKeeperResolver.resolve(state)
        );

        assertEquals(
                "Artifact Keeper user not found: missing-user",
                exception.getMessage()
        );
    }

    @Test
    void rejectsUnknownRepository() {
        var state = new ArtifactKeeperState(
                List.of(new ArtifactKeeperApiUser("user-1", "alice")),
                List.of(),
                List.of(new ArtifactKeeperApiPermission(
                        "permission-1",
                        "user",
                        "user-1",
                        "repository",
                        "missing-repository",
                        Set.of("read")
                ))
        );

        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> ArtifactKeeperResolver.resolve(state)
        );

        assertEquals(
                "Artifact Keeper repository not found: missing-repository",
                exception.getMessage()
        );
    }

    @Test
    void resolvesEmptyState() {
        var state = new ArtifactKeeperState(
                List.of(),
                List.of(),
                List.of()
        );

        assertTrue(ArtifactKeeperResolver.resolve(state).isEmpty());
    }
}
