package io.github.pactproject.artifactkeeper.compile;

import io.github.pactproject.api.Access;
import io.github.pactproject.api.PactState;
import io.github.pactproject.api.Resource;
import io.github.pactproject.api.value.Value;
import io.github.pactproject.artifactkeeper.model.ArtifactKeeperPermission;
import io.github.pactproject.artifactkeeper.sync.ArtifactKeeperCompiler;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ArtifactKeeperCompilerTest {

    private static final Resource REPOSITORY = new Resource(
            "artifact-keeper",
            Map.of("repository", "repository-a")
    );

    @Test
    void compilesRegistryAccessForEachUser() {
        PactState state = new PactState(Set.of(
                access(
                        "alice",
                        Set.of("read", "write")
                ),
                access(
                        "bob",
                        Set.of("read", "write")
                )
        ));

        List<ArtifactKeeperPermission> result =
                ArtifactKeeperCompiler.compile(state);

        assertEquals(
                Set.of(
                        permission("alice", "read", "write"),
                        permission("bob", "read", "write")
                ),
                Set.copyOf(result)
        );
    }

    @Test
    void mergesRepeatedAccessEntriesForSameUserAndRepository() {
        PactState state = new PactState(Set.of(
                access("alice", Set.of("read")),
                access("alice", Set.of("write"))
        ));

        List<ArtifactKeeperPermission> result =
                ArtifactKeeperCompiler.compile(state);

        assertEquals(
                Set.of(permission("alice", "read", "write")),
                Set.copyOf(result)
        );
    }

    @Test
    void deduplicatesActions() {
        PactState state = new PactState(Set.of(
                access("alice", Set.of("read")),
                access("alice", Set.of("read", "write")),
                access("alice", Set.of("write"))
        ));

        List<ArtifactKeeperPermission> result =
                ArtifactKeeperCompiler.compile(state);

        assertEquals(
                Set.of(permission("alice", "read", "write")),
                Set.copyOf(result)
        );
    }

    @Test
    void ignoresEmptyUsers() {
        PactState state = new PactState(Set.of());

        List<ArtifactKeeperPermission> result =
                ArtifactKeeperCompiler.compile(state);

        assertTrue(result.isEmpty());
    }

    @Test
    void ignoresAccessWithoutPermissions() {
        Access access = new Access(
                "alice",
                REPOSITORY,
                Map.of()
        );

        List<ArtifactKeeperPermission> result =
                ArtifactKeeperCompiler.compile(
                        new PactState(Set.of(access))
                );

        assertTrue(result.isEmpty());
    }

    @Test
    void rejectsMissingRepositoryTarget() {
        Access access = new Access(
                "alice",
                new Resource(
                        "artifact-keeper",
                        Map.of()
                ),
                Map.of(
                        "actions",
                        Value.set(Set.of(Value.string("read")))
                )
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> ArtifactKeeperCompiler.compile(
                        new PactState(Set.of(access))
                )
        );
    }

    @Test
    void rejectsNonStringActions() {
        Access access = new Access(
                "alice",
                REPOSITORY,
                Map.of(
                        "actions",
                        Value.set(Set.of(
                                Value.string("read"),
                                Value.number(java.math.BigDecimal.ONE)
                        ))
                )
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> ArtifactKeeperCompiler.compile(
                        new PactState(Set.of(access))
                )
        );
    }

    @Test
    void rejectsActionsWithWrongValueType() {
        Access access = new Access(
                "alice",
                REPOSITORY,
                Map.of(
                        "actions",
                        Value.string("read")
                )
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> ArtifactKeeperCompiler.compile(
                        new PactState(Set.of(access))
                )
        );
    }

    private static Access access(String username, Set<String> actions) {
        return new Access(
                username,
                REPOSITORY,
                Map.of(
                        "actions",
                        Value.set(
                                actions.stream()
                                        .map(Value::string)
                                        .collect(java.util.stream.Collectors.toSet())
                        )
                )
        );
    }

    private static ArtifactKeeperPermission permission(
            String username,
            String... actions
    ) {
        return new ArtifactKeeperPermission(
                "repository-a",
                username,
                Set.of(actions)
        );
    }
}
