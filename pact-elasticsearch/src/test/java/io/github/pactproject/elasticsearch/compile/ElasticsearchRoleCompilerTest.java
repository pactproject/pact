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
    void requiresExactClusterTargetAndSeparatesClusterFromIndexAttributes() {
        Map<String, Value> roles = Map.of("roles", Value.set(Set.of(Value.string("monitoring_user"))));
        var valid = new Access("alice", new Resource("search", Map.of("cluster", "*")), roles);
        assertEquals(Set.of("monitoring_user"), ElasticsearchRoleCompiler.compile(new PactState(Set.of(valid)), "search").get("alice").assignedRoles());
        for (Map<String, String> target : java.util.List.of(Map.of("cluster", "other"), Map.of("type", "cluster"), Map.of("index", "logs-*"))) {
            assertThrows(IllegalArgumentException.class, () -> ElasticsearchRoleCompiler.compile(
                    new PactState(Set.of(new Access("alice", new Resource("search", target), roles))), "search"));
        }
    }
    @Test
    void aggregatesPrivilegesPerPrincipalAndIndexAndNamesRolesDeterministically() {
        PactState state = new PactState(Set.of(
                access("alice", "logs-*", "read"),
                access("alice", "logs-*", "view_index_metadata"),
                access("alice", "archive-*", "read")
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
    void rejectsForeignBackendIdInsteadOfIgnoringAccess() {
        var foreign = new Access("alice", new Resource("other", Map.of("index", "logs-*")),
                permissions("read"));
        assertThrows(IllegalArgumentException.class, () -> ElasticsearchRoleCompiler.compile(
                new PactState(Set.of(access("alice", "archive-*", "read"), foreign)), "search"));
    }

    @Test
    void rejectsMalformedTargetAndPermissionShape() {
        Access badTarget = new Access(
                "alice",
                new Resource("search", Map.of(
                        "index", "logs-*", "cluster", "all"
                )),
                permissions("read")
        );
        Access badPrivilege = new Access("alice", new Resource("search", Map.of("index", "logs-*")),
                Map.of("permissions", Value.object(Map.of("cluster", Value.set(Set.of(Value.string("monitor")))))));

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
