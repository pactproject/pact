package io.github.pactproject.grafana;

import io.github.pactproject.api.*;
import io.github.pactproject.api.exception.ValidationException;
import io.github.pactproject.api.value.Value;
import io.github.pactproject.grafana.compile.GrafanaCompiler;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GrafanaCompilerTest {
    static Access access(String principal, Map<String, String> target, String... permissions) {
        return new Access(principal, new Resource("monitoring", target), Map.of("permissions",
                Value.set(Arrays.stream(permissions).map(Value::string).collect(java.util.stream.Collectors.toSet()))));
    }
    static PactState state(Access... accesses) { return new PactState(Set.of(accesses)); }

    @Test void acceptsHierarchyAndUidSelectorsAndAggregatesPermissionLevelsDeterministically() throws Exception {
        var accesses = List.of(access("bob", Map.of("dashboardUid", "uid"), "View"),
                access("alice", Map.of("folder", "Kubernetes/Production", "dashboard", "CPU / Usage"), "view", "EDIT"),
                access("alice", Map.of("folderUid", "folder"), "Admin"),
                access("alice", Map.of("folder", "/", "dashboard", "Root"), "View"));
        var compiled = GrafanaCompiler.compile(new PactState(new HashSet<>(accesses)), "monitoring");
        assertEquals(4, compiled.size());
        assertTrue(compiled.stream().anyMatch(entry -> entry.permission() == 2 && "CPU / Usage".equals(entry.target().dashboard())));
        assertEquals(compiled, GrafanaCompiler.compile(new PactState(new LinkedHashSet<>(accesses.reversed())), "monitoring"));
    }

    @Test void rejectsInvalidTargetsAliasesPrivilegesAttributesAndForeignBackendIds() {
        for (var target : List.of(Map.<String, String>of(), Map.of("dashboard", "CPU"), Map.of("folder", "/"),
                Map.of("folder", "A", "folderUid", "b"), Map.of("dashboard", "A", "dashboardUid", "b", "folder", "F"),
                Map.of("folder", "A//B"), Map.of("folder", "/A"), Map.of("folder", "A/"),
                Map.of("folder", ""), Map.of("index", "logs"))) {
            assertThrows(ValidationException.class, () -> GrafanaCompiler.compile(state(access("alice", target, "View")), "monitoring"));
        }
        for (String permission : List.of("read", "write", "deny", "")) {
            assertThrows(ValidationException.class, () -> GrafanaCompiler.compile(state(access("alice", Map.of("folderUid", "f"), permission)), "monitoring"));
        }
        assertThrows(ValidationException.class, () -> GrafanaCompiler.compile(state(access("alice", Map.of("folderUid", "f"))), "monitoring"));
        assertThrows(ValidationException.class, () -> GrafanaCompiler.compile(state(access(" ", Map.of("folderUid", "f"), "View")), "monitoring"));
        var valid = access("alice", Map.of("folderUid", "f"), "View");
        assertThrows(ValidationException.class, () -> GrafanaCompiler.compile(state(valid), "another-backend"));
        for (var attributes : List.of(Map.of("permission", Value.string("View")), Map.of("permissions", Value.string("View")),
                Map.of("permissions", Value.set(Set.of(Value.bool(true)))))) {
            assertThrows(ValidationException.class, () -> GrafanaCompiler.compile(state(new Access("alice", valid.resource(), new HashMap<>(attributes))), "monitoring"));
        }
    }
}
