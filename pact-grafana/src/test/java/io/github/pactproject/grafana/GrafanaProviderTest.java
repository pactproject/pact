package io.github.pactproject.grafana;

import io.github.pactproject.api.PactState;
import io.github.pactproject.core.PactCore;
import io.github.pactproject.filesystem.FileSystemStateProvider;
import io.github.pactproject.kubernetes.compile.DataAccessCompiler;
import io.github.pactproject.grafana.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GrafanaProviderTest {
    @TempDir Path directory;

    @Test void filesystemLoadsNamedTargetsThroughCoreAndCleansUsingSameInstance() throws Exception {
        var file = directory.resolve("access.yaml");
        Files.writeString(file, """
                accesses:
                  - principal: alice
                    resource:
                      backendId: dashboards-east
                      target: {folder: Kubernetes, dashboard: CPU}
                    attributes:
                      permissions: [View]
                """);
        var client = fixture();
        try (var core = new PactCore(List.of(new GrafanaBackend("dashboards-east", client)))) {
            var provider = new FileSystemStateProvider(file);
            core.apply(provider.load());
            assertEquals(List.of(GrafanaPermission.user(10, 1)), client.acls.get(dashboard()));
            Files.writeString(file, "accesses: []\n");
            core.apply(provider.load());
            assertTrue(client.acls.get(dashboard()).isEmpty());
        }
    }

    @Test void kubernetesGenericPathPreservesHierarchyAndOverlappingDeclarations() throws Exception {
        var compiler = new DataAccessCompiler();
        Map<String, Object> first = Map.of("resources", List.of(Map.of("dashboards-east", Map.of("folder", "Kubernetes", "dashboard", "CPU"),
                "access", List.of(Map.of("users", List.of("alice"), "permissions", List.of("View"))))));
        Map<String, Object> second = Map.of("resources", List.of(Map.of("dashboards-east", Map.of("dashboardUid", "cpu"),
                "access", List.of(Map.of("users", List.of("alice"), "permissions", List.of("Edit"))))));
        var client = fixture();
        try (var core = new PactCore(List.of(new GrafanaBackend("dashboards-east", client)))) {
            core.apply(compiler.compile(List.of(first, second)));
            assertEquals(List.of(GrafanaPermission.user(10, 2)), client.acls.get(dashboard()));
            core.apply(compiler.compile(List.of(first)));
            assertEquals(List.of(GrafanaPermission.user(10, 1)), client.acls.get(dashboard()));
            core.apply(PactState.empty());
            assertTrue(client.acls.get(dashboard()).isEmpty());
        }
    }

    private FakeGrafanaClient fixture() {
        var client = new FakeGrafanaClient();
        client.add(GrafanaResource.Kind.FOLDER, "k8s", "Kubernetes", "");
        client.add(GrafanaResource.Kind.DASHBOARD, "cpu", "CPU", "k8s");
        return client;
    }
    private GrafanaResource dashboard() { return new GrafanaResource(GrafanaResource.Kind.DASHBOARD, "cpu"); }
}
