package io.github.pactproject.grafana;

import com.fasterxml.jackson.databind.*;
import io.github.pactproject.api.*;
import io.github.pactproject.api.exception.BackendOperationException;
import io.github.pactproject.core.PactCore;
import io.github.pactproject.filesystem.FileSystemStateProvider;
import io.github.pactproject.grafana.api.*;
import io.github.pactproject.grafana.client.GrafanaHttpClient;
import io.github.pactproject.grafana.model.*;
import io.github.pactproject.kubernetes.compile.DataAccessCompiler;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.pactproject.grafana.GrafanaCompilerTest.*;

/** Uses fixture-created local users to test authorization; the plugin never creates users. */
@EnabledIfSystemProperty(named = "pact.integration", matches = "true")
class GrafanaLiveIntegrationTest {
    final ObjectMapper json = new ObjectMapper();
    HttpClient http;
    String base, admin, principal, password, readerAuth, folderUid, nestedUid, dashboardUid, unrelatedUid, unrelatedFolderUid;
    long userId, serviceAccountId;
    GrafanaHttpClient nativeClient;
    @TempDir Path directory;

    @BeforeEach void setup() throws Exception {
        Assumptions.assumeTrue("true".equals(System.getenv("PACT_IT_GRAFANA_DISPOSABLE")), "requires an explicitly disposable Grafana fixture");
        base = System.getenv().getOrDefault("PACT_IT_GRAFANA_URL", "http://127.0.0.1:33080");
        admin = basic("pact-fixture-admin", "pact-disposable-grafana");
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        String suffix = UUID.randomUUID().toString().substring(0, 12);
        principal = "pact-it-" + suffix;
        password = UUID.randomUUID().toString();
        readerAuth = basic(principal, password);
        folderUid = "pact-f-" + suffix;
        nestedUid = "pact-n-" + suffix;
        dashboardUid = "pact-d-" + suffix;
        unrelatedFolderUid = "pact-u-" + suffix;
        unrelatedUid = "pact-o-" + suffix;
        userId = create("api/admin/users", Map.of("name", principal, "email", principal + "@example.invalid", "login", principal, "password", password)).path("id").asLong();
        serviceAccountId = create("api/serviceaccounts", Map.of("name", "pact-sa-" + suffix, "role", "Admin")).path("id").asLong();
        assertTrue(userId > 0 && serviceAccountId > 0);
        String token = create("api/serviceaccounts/" + serviceAccountId + "/tokens", Map.of("name", "pact-test")).path("key").asText();
        assertFalse(token.isBlank());
        nativeClient = new GrafanaHttpClient(GrafanaConfig.from(Map.of("endpoint", base, "token", token, "org-id", "1", "page-size", "1")));
        create("api/folders", Map.of("uid", folderUid, "title", principal));
        create("api/folders", Map.of("uid", nestedUid, "title", "Nested", "parentUid", folderUid));
        create("api/folders", Map.of("uid", unrelatedFolderUid, "title", principal + "-other"));
        createDashboard(dashboardUid, nestedUid);
        createDashboard(unrelatedUid, unrelatedFolderUid);
        for (var resource : List.of(folder(folderUid), folder(nestedUid), folder(unrelatedFolderUid), dashboard(dashboardUid), dashboard(unrelatedUid))) {
            nativeClient.replacePermissions(resource, List.of());
        }
    }

    @AfterEach void cleanup() throws Exception {
        if (http == null) return;
        try {
            // Only explicitly created fixture IDs are removed; never sweep the server catalog.
            if (dashboardUid != null) call("DELETE", "api/dashboards/uid/" + dashboardUid, null, admin);
            if (unrelatedUid != null) call("DELETE", "api/dashboards/uid/" + unrelatedUid, null, admin);
            if (nestedUid != null) call("DELETE", "api/folders/" + nestedUid, null, admin);
            if (folderUid != null) call("DELETE", "api/folders/" + folderUid, null, admin);
            if (unrelatedFolderUid != null) call("DELETE", "api/folders/" + unrelatedFolderUid, null, admin);
            if (userId > 0) call("DELETE", "api/admin/users/" + userId, null, admin);
            if (serviceAccountId > 0) call("DELETE", "api/serviceaccounts/" + serviceAccountId, null, admin);
        } finally {
            if (nativeClient != null) nativeClient.close();
            http.shutdownNow();
        }
    }

    @Test void recreatedDashboardTitleNeedsFreshPreparationAndRevokesTheReplacement() throws Exception {
        var counted = new ForwardingClient(nativeClient);
        var backend = new GrafanaBackend("monitoring", counted);
        var desired = state(access(principal, Map.of("folderUid", nestedUid, "dashboard", "CPU / Usage"), "View"));
        var pending = backend.prepareTransaction(PactState.empty(), desired);
        String oldUid = dashboardUid;
        assertEquals(200, call("DELETE", "api/dashboards/uid/" + oldUid, null, admin).statusCode());
        dashboardUid = "pact-recreated-" + UUID.randomUUID().toString().substring(0, 12);
        createDashboard(dashboardUid, nestedUid);
        nativeClient.replacePermissions(dashboard(dashboardUid), List.of());
        assertDenied(dashboardUid);
        assertThrows(io.github.pactproject.api.exception.ValidationException.class, pending::apply);
        assertEquals(0, counted.writes);
        assertThrows(io.github.pactproject.api.exception.ValidationException.class,
                () -> backend.apply(state(access(principal, Map.of("dashboardUid", oldUid), "View"))));
        backend.prepareTransaction(PactState.empty(), desired).apply();
        assertEquals(200, call("GET", "api/dashboards/uid/" + dashboardUid, null, readerAuth).statusCode());
        assertEquals(404, call("GET", "api/dashboards/uid/" + oldUid, null, admin).statusCode());
        backend.prepareTransaction(desired, PactState.empty()).apply();
        assertDenied(dashboardUid);
    }

    @Test void recreatedLoginGetsOnlyFreshlyResolvedUserAclAndNeverUsesPreparedOldId() throws Exception {
        var counted = new ForwardingClient(nativeClient);
        var backend = new GrafanaBackend("monitoring", counted);
        var desired = state(access(principal, Map.of("dashboardUid", dashboardUid), "View"));
        var pending = backend.prepareTransaction(PactState.empty(), desired);
        long oldId = userId;
        assertEquals(200, call("DELETE", "api/admin/users/" + oldId, null, admin).statusCode());
        password = UUID.randomUUID().toString();
        userId = create("api/admin/users", Map.of("name", principal, "email", principal + "@example.invalid",
                "login", principal, "password", password)).path("id").asLong();
        assertNotEquals(oldId, userId);
        readerAuth = basic(principal, password);
        assertThrows(io.github.pactproject.api.exception.ValidationException.class, pending::apply);
        assertEquals(0, counted.writes);
        assertDenied(dashboardUid);
        backend.prepareTransaction(PactState.empty(), desired).apply();
        assertEquals(List.of(GrafanaPermission.user(userId, 1)), nativeClient.permissions(dashboard(dashboardUid)));
        assertEquals(200, call("GET", "api/dashboards/uid/" + dashboardUid, null, readerAuth).statusCode());
        backend.prepareTransaction(desired, PactState.empty()).apply();
        assertDenied(dashboardUid);
    }

    @Test void filesystemAndKubernetesDeclarationsEnforceNamedUidPermissionsAndRevocation() throws Exception {
        assertDenied(dashboardUid);
        assertDenied(unrelatedUid);
        var file = directory.resolve("state.yaml");
        Files.writeString(file, """
                accesses:
                  - principal: %s
                    resource:
                      backendId: monitoring
                      target:
                        folder: %s/Nested
                        dashboard: CPU / Usage
                    attributes:
                      permissions: [View]
                """.formatted(principal, principal));
        var provider = new FileSystemStateProvider(file);
        var counted = new ForwardingClient(nativeClient);
        try (var core = new PactCore(List.of(new GrafanaBackend("monitoring", counted)))) {
            core.apply(provider.load());
            assertEquals(200, call("GET", "api/dashboards/uid/" + dashboardUid, null, readerAuth).statusCode());
            assertFalse(json.readTree(call("GET", "api/dashboards/uid/" + dashboardUid, null, readerAuth).body()).path("meta").path("canEdit").asBoolean());
            assertEquals(403, editAsReader().statusCode());
            assertDenied(unrelatedUid);
            int writes = counted.writes;
            var uidState = state(access(principal, Map.of("dashboardUid", dashboardUid), "View"));
            core.apply(uidState);
            assertEquals(writes, counted.writes, "name/UID equivalence must not rewrite ACLs");
            var compiled = new DataAccessCompiler().compile(List.of(Map.of("resources", List.of(Map.of("monitoring", Map.of("dashboardUid", dashboardUid),
                    "access", List.of(Map.of("users", List.of(principal), "permissions", List.of("Edit"))))))));
            core.apply(compiled);
            assertTrue(json.readTree(call("GET", "api/dashboards/uid/" + dashboardUid, null, readerAuth).body()).path("meta").path("canEdit").asBoolean());
            assertEquals(200, editAsReader().statusCode());
            Files.writeString(file, "accesses: []\n");
            core.apply(provider.load());
            assertTrue(nativeClient.permissions(dashboard(dashboardUid)).isEmpty());
            assertDenied(dashboardUid);
        }
    }

    @Test void folderInheritanceAndRealLostWriteCompensationRespectPreviousDeclaration() throws Exception {
        var fault = new ForwardingClient(nativeClient);
        var backend = new GrafanaBackend("monitoring", fault);
        var previous = state(access(principal, Map.of("folderUid", folderUid), "View"));
        backend.apply(previous);
        assertEquals(200, call("GET", "api/dashboards/uid/" + dashboardUid, null, readerAuth).statusCode());
        // Dashboard direct ACL cleanup cannot revoke inherited folder access.
        nativeClient.replacePermissions(dashboard(dashboardUid), List.of());
        assertEquals(200, call("GET", "api/dashboards/uid/" + dashboardUid, null, readerAuth).statusCode());
        var desired = state(access(principal, Map.of("dashboardUid", unrelatedUid), "Edit"));
        var transaction = backend.prepareTransaction(previous, desired);
        fault.failAt = fault.writes + 2;
        assertThrows(BackendOperationException.class, transaction::apply);
        assertEquals(200, call("GET", "api/dashboards/uid/" + unrelatedUid, null, readerAuth).statusCode());
        transaction.rollback();
        assertEquals(List.of(GrafanaPermission.user(userId, 1)), nativeClient.permissions(folder(folderUid)));
        assertTrue(nativeClient.permissions(dashboard(unrelatedUid)).isEmpty());
        assertDenied(unrelatedUid);
        assertEquals(200, call("GET", "api/dashboards/uid/" + dashboardUid, null, readerAuth).statusCode());
        backend.prepareTransaction(previous, desired).apply();
        assertDenied(dashboardUid);
        backend.prepareTransaction(desired, PactState.empty()).apply();
        assertDenied(unrelatedUid);
    }

    void createDashboard(String uid, String parent) throws Exception {
        create("api/dashboards/db", Map.of("folderUid", parent, "dashboard", Map.of("uid", uid, "title", "CPU / Usage", "schemaVersion", 41, "panels", List.of()), "overwrite", false));
    }
    HttpResponse<String> editAsReader() throws Exception {
        var dashboard = json.readTree(call("GET", "api/dashboards/uid/" + dashboardUid, null, admin).body()).get("dashboard");
        ((com.fasterxml.jackson.databind.node.ObjectNode) dashboard).put("description", "edited by fixture reader");
        return call("POST", "api/dashboards/db", json.writeValueAsString(Map.of("folderUid", nestedUid, "dashboard", dashboard, "overwrite", false)), readerAuth);
    }
    JsonNode create(String path, Object body) throws Exception {
        var response = call("POST", path, json.writeValueAsString(body), admin);
        assertTrue(response.statusCode() == 200 || response.statusCode() == 201, path + " status=" + response.statusCode());
        return json.readTree(response.body());
    }
    HttpResponse<String> call(String method, String path, String body, String authentication) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + "/" + path)).timeout(Duration.ofSeconds(10))
                .header("Authorization", authentication).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
    void assertDenied(String uid) throws Exception {
        int status = call("GET", "api/dashboards/uid/" + uid, null, readerAuth).statusCode();
        assertTrue(status == 403 || status == 404, "expected denial, status=" + status);
    }
    static String basic(String user, String password) { return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8)); }
    static GrafanaResource folder(String uid) { return new GrafanaResource(GrafanaResource.Kind.FOLDER, uid); }
    static GrafanaResource dashboard(String uid) { return new GrafanaResource(GrafanaResource.Kind.DASHBOARD, uid); }

    static final class ForwardingClient implements GrafanaClient {
        final GrafanaClient delegate;
        int writes, failAt = -1;
        ForwardingClient(GrafanaClient delegate) { this.delegate = delegate; }
        @Override public void verifyOrganization() throws GrafanaClientException { delegate.verifyOrganization(); }
        @Override public List<GrafanaUser> users() throws GrafanaClientException { return delegate.users(); }
        @Override public List<GrafanaObject> objects() throws GrafanaClientException { return delegate.objects(); }
        @Override public List<GrafanaPermission> permissions(GrafanaResource resource) throws GrafanaClientException { return delegate.permissions(resource); }
        @Override public void replacePermissions(GrafanaResource resource, List<GrafanaPermission> permissions) throws GrafanaClientException {
            delegate.replacePermissions(resource, permissions);
            if (++writes == failAt) throw new GrafanaClientException("Injected lost response after real Grafana ACL write");
        }
        // Fixture owns and closes the shared HTTP client after cleanup.
    }
}
