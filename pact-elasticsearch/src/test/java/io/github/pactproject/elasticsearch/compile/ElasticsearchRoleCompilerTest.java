package io.github.pactproject.elasticsearch.compile;

import io.github.pactproject.api.Access;
import io.github.pactproject.api.PactState;
import io.github.pactproject.api.Resource;
import io.github.pactproject.api.value.Value;
import io.github.pactproject.elasticsearch.model.ElasticsearchRole;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ElasticsearchRoleCompilerTest {
    @Test
    void aggregatesPrivilegesPerPrincipalAndIndexAndNamesRolesDeterministically() {
        PactState state = new PactState(Set.of(
                access("alice", "logs-*", "read"),
                access("alice", "logs-*", "view_index_metadata"),
                access("alice", "archive-*", "read"),
                new Access(
                        "ignored",
                        new Resource("other", Map.of("index", "unused")),
                        Map.of("permissions", Value.object(Map.of(
                                "indices", Value.set(Set.of(Value.string("read")))
                        )))
                )
        ));

        Map<String, ElasticsearchRole> result =
                ElasticsearchRoleCompiler.compile(state, "search");

        assertEquals(Set.of("alice"), result.keySet());
        assertEquals(Map.of(
                "archive-*", java.util.List.of("read"),
                "logs-*", java.util.List.of("read", "view_index_metadata")
        ), result.get("alice").indices());
        assertEquals(result.get("alice").name(),
                ElasticsearchRoleCompiler.roleName("search", "alice"));
        assertNotEquals(
                ElasticsearchRoleCompiler.roleName("search", "alice"),
                ElasticsearchRoleCompiler.roleName("other", "alice")
        );
    }

    @Test
    void rejectsMalformedTargetAndUnsupportedPrivilege() {
        Access badTarget = new Access(
                "alice",
                new Resource("search", Map.of(
                        "index", "logs-*", "cluster", "all"
                )),
                permissions("read")
        );
        Access badPrivilege = access("alice", "logs-*", "manage_everything");

        assertThrows(IllegalArgumentException.class, () ->
                ElasticsearchRoleCompiler.compile(
                        new PactState(Set.of(badTarget)), "search"));
        assertThrows(IllegalArgumentException.class, () ->
                ElasticsearchRoleCompiler.compile(
                        new PactState(Set.of(badPrivilege)), "search"));
    }

    private static Access access(
            String principal,
            String index,
            String privilege
    ) {
        return new Access(
                principal,
                new Resource("search", Map.of("index", index)),
                permissions(privilege)
        );
    }

    private static Map<String, Value> permissions(String... privileges) {
        return Map.of(
                "permissions",
                Value.object(Map.of(
                        "indices",
                        Value.set(java.util.Arrays.stream(privileges)
                                .map(Value::string)
                                .collect(java.util.stream.Collectors.toSet()))
                ))
        );
    }
}
