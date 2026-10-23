package io.github.pactproject.artifactkeeper.compile;

import io.github.pactproject.api.Access;
import io.github.pactproject.api.PactState;
import io.github.pactproject.api.Resource;
import io.github.pactproject.api.value.Value;
import io.github.pactproject.artifactkeeper.model.ArtifactKeeperPermission;
import io.github.pactproject.artifactkeeper.sync.ArtifactKeeperCompiler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
    void compilesServiceAccountPrincipals() {
        List<ArtifactKeeperPermission> result =
                ArtifactKeeperCompiler.compile(new PactState(Set.of(
                        access("svc-build", Set.of("read"))
                )));

        assertEquals(
                List.of(new ArtifactKeeperPermission(
                        "repository-a",
                        "service_account",
                        "svc-build",
                        Set.of("read")
                )),
                result
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

    @ParameterizedTest
    @ValueSource(strings = {"not-a-uuid", "1-1-1-1-1", "", "https://example.org"})
    void rejectsInvalidTargetIds(String id) {
        assertThrows(IllegalArgumentException.class, () -> ArtifactKeeperCompiler.compile(new PactState(Set.of(
                new Access("alice", new Resource("registry", Map.of("type", "artifact", "id", id)),
                        access("alice", Set.of("read")).attributes())))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"10.0.0.0/33", "256.0.0.1/8", "example.org/24", "::/129", "10.0.0/8"})
    void rejectsInvalidCidrs(String cidr) {
        assertThrows(IllegalArgumentException.class, () -> ArtifactKeeperCompiler.compile(new PactState(Set.of(
                withConditions(Map.of("allowed_cidrs", Value.set(Set.of(Value.string(cidr)))))))));
    }

    @Test
    void validatesConditionsAndDetectsConflictingRules() {
        for (var conditions : List.of(
                Map.of("allowed_cidrs", Value.set(Set.of())),
                Map.of("allowed_cidrs", Value.string("10.0.0.0/8")),
                Map.of("unknown_condition", Value.string("value")))) {
            assertThrows(IllegalArgumentException.class, () -> ArtifactKeeperCompiler.compile(new PactState(Set.of(
                    withConditions(Map.copyOf(conditions))))));
        }
        var conditional = withConditions(Map.of("allowed_cidrs", Value.set(Set.of(
                Value.string("10.0.0.0/8"), Value.string("2001:db8::/32")))));
        assertEquals(1, ArtifactKeeperCompiler.compile(new PactState(Set.of(conditional))).size());
        assertThrows(IllegalArgumentException.class, () -> ArtifactKeeperCompiler.compile(new PactState(Set.of(
                conditional, access("alice", Set.of("write"))))));
    }

    @Test
    void restrictsAnonymousGrantsToReadOnRepositoriesAndProjects() {
        for (var target : List.of(Map.of("repository", "repo"), Map.of("type", "artifact", "id",
                "11111111-1111-1111-1111-111111111111"))) {
            var anonymousWrite = new Access("anonymous", new Resource("registry", target), Map.of(
                    "principal-type", Value.string("anonymous"),
                    "actions", Value.set(Set.of(Value.string("write")))));
            assertThrows(IllegalArgumentException.class, () -> ArtifactKeeperCompiler.compile(
                    new PactState(Set.of(anonymousWrite))));
        }
    }

    private static Access withConditions(Map<String, Value> conditions) {
        return new Access("alice", REPOSITORY, Map.of(
                "actions", Value.set(Set.of(Value.string("read"))), "conditions", Value.object(conditions)));
    }

    private static ArtifactKeeperPermission permission(
            String username,
            String... actions
    ) {
        return new ArtifactKeeperPermission(
                "repository-a",
                "user",
                username,
                Set.of(actions)
        );
    }
}
