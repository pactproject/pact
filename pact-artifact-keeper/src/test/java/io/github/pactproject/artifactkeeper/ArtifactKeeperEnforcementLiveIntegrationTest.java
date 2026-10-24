package io.github.pactproject.artifactkeeper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.pactproject.api.Access;
import io.github.pactproject.api.PactState;
import io.github.pactproject.api.Resource;
import io.github.pactproject.api.value.Value;
import io.github.pactproject.artifactkeeper.api.ArtifactKeeperApiPermission;
import io.github.pactproject.artifactkeeper.client.ArtifactKeeperHttpClient;
import io.github.pactproject.core.PactCore;
import io.github.pactproject.core.exception.ApplyException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** Effective authorization checks, always against an isolated 1.11.1/current instance. */
@EnabledIfSystemProperty(named = "pact.integration", matches = "true")
@EnabledIfEnvironmentVariable(named = "PACT_IT_ARTIFACT_KEEPER_API_PROFILE", matches = "current")
class ArtifactKeeperEnforcementLiveIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Test
    @EnabledIfEnvironmentVariable(named = "PACT_IT_ARTIFACT_KEEPER_ALLOWED_PROBE_URL", matches = ".+")
    void enforcesCidrsUsingDistinctTcpPeersAndDoesNotTrustSpoofedForwardingHeaders() throws Exception {
        try (var fixture = new Fixture()) {
            String repo = fixture.repository(null);
            String token = fixture.serviceAccountToken();
            String path = "/api/v1/repositories/" + repo + "/download/app.bin";
            String allowed = required("PACT_IT_ARTIFACT_KEEPER_ALLOWED_PROBE_URL");
            String denied = required("PACT_IT_ARTIFACT_KEEPER_DENIED_PROBE_URL");
            String allowedIp = InetAddress.getByName(required("PACT_IT_ARTIFACT_KEEPER_ALLOWED_PROBE_HOST")).getHostAddress();
            var desired = state(fixture.principal, Map.of("name", repo), Set.of("read"), allowedIp + "/32");

            assertDenied(request(allowed, token, "GET", path, null).statusCode());
            fixture.backend.apply(desired);
            // Alternate peers repeatedly to catch authorization-cache entries that omit the client IP.
            for (int i = 0; i < 3; i++) {
                var accepted = request(allowed, token, "GET", path, null);
                assertEquals(200, accepted.statusCode(), "Allowed TCP peer must download the artifact");
                assertEquals("{\"fixture\":\"content\"}", accepted.body());
                var spoofed = HttpRequest.newBuilder(URI.create(denied + path))
                        .timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + token)
                        .header("X-Forwarded-For", allowedIp).header("X-Real-IP", allowedIp)
                        .header("Forwarded", "for=" + allowedIp).GET().build();
                assertDenied(HTTP.send(spoofed, HttpResponse.BodyHandlers.ofString()).statusCode());
            }
            fixture.backend.apply(PactState.empty());
            assertDenied(request(allowed, token, "GET", path, null).statusCode());
        }
    }

    @Test
    void projectGrantAllowsItsArtifactsButDeniesAnotherProjectAndRevocationTakesEffect() throws Exception {
        try (var fixture = new Fixture()) {
            String projectName = "PACT access boundary " + UUID.randomUUID();
            String project = fixture.project(projectName);
            String otherProject = fixture.project("PACT unrelated " + UUID.randomUUID());
            String first = fixture.repository(project);
            String second = fixture.repository(project);
            String outside = fixture.repository(otherProject);
            String token = fixture.serviceAccountToken();
            for (String repo : List.of(first, second, outside)) fixture.assertDownload(repo, token, false);
            fixture.backend.apply(state(fixture.principal, Map.of("type", "project", "name", projectName), Set.of("read"), null));
            fixture.assertDownload(first, token, true);
            fixture.assertDownload(second, token, true);
            fixture.assertDownload(outside, token, false);
            fixture.backend.apply(PactState.empty());
            fixture.assertDownload(first, token, false);
            fixture.assertDownload(second, token, false);
            fixture.assertDownload(outside, token, false);
        }
    }

    @Test
    void artifactOnlyGrantDoesNotBypassRepositoryDownloadGateIn1111() throws Exception {
        try (var fixture = new Fixture()) {
            String repo = fixture.repository(null);
            fixture.api("PUT", "/api/v1/repositories/" + repo + "/artifacts/sibling.bin", Map.of("fixture", "sibling"));
            String token = fixture.serviceAccountToken();
            var desired = state(fixture.principal,
                    Map.of("type", "artifact", "repository", repo, "path", "app.bin"), Set.of("read"), null);
            fixture.backend.apply(desired);
            assertEquals("artifact", fixture.permissions().getFirst().targetType());
            // This is an upstream limitation, not a successful per-artifact authorization test.
            fixture.assertDownload(repo, token, false);
            assertDenied(request(fixture.url, token, "GET",
                    "/api/v1/repositories/" + repo + "/download/sibling.bin", null).statusCode());
            fixture.backend.apply(state(fixture.principal, Map.of("name", repo), Set.of("read"), null));
            fixture.assertDownload(repo, token, true);
            assertEquals(200, request(fixture.url, token, "GET",
                    "/api/v1/repositories/" + repo + "/download/sibling.bin", null).statusCode());
        }
    }

    @Test
    void coreCompensatesARealDeletionAfterOneHttpWriteFailsAndRestoresCidrs() throws Exception {
        try (var fixture = new Fixture()) {
            String repo = fixture.repository(null);
            fixture.serviceAccountToken();
            var previous = state(fixture.principal, Map.of("name", repo), Set.of("read"), "10.20.0.0/16");
            fixture.backend.apply(previous);
            ArtifactKeeperApiPermission snapshot = fixture.permissions().getFirst();
            var events = java.util.Collections.synchronizedList(new ArrayList<String>());
            var inject = new AtomicBoolean(true);
            var deleted = new AtomicBoolean();
            HttpServer proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            proxy.createContext("/", exchange -> {
                try {
                    String method = exchange.getRequestMethod();
                    String path = exchange.getRequestURI().toString();
                    int code;
                    byte[] body;
                    if (method.equals("POST") && path.equals("/api/v1/permissions") && deleted.get()
                            && inject.compareAndSet(true, false)) {
                        code = 503;
                        body = "{\"error\":\"intentional test fault\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    } else {
                        var builder = HttpRequest.newBuilder(URI.create(fixture.url + path))
                                .timeout(Duration.ofSeconds(30))
                                .header("Authorization", "Bearer " + fixture.admin)
                                .header("Content-Type", "application/json")
                                .method(method, HttpRequest.BodyPublishers.ofByteArray(exchange.getRequestBody().readAllBytes()));
                        var response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
                        code = response.statusCode();
                        body = response.body();
                        if (method.equals("DELETE") && path.startsWith("/api/v1/permissions/") && code < 300) deleted.set(true);
                    }
                    if ((method.equals("POST") || method.equals("DELETE")) && path.startsWith("/api/v1/permissions")) {
                        events.add(method + " " + code);
                    }
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(code, code == 204 ? -1 : body.length);
                    if (code != 204) exchange.getResponseBody().write(body);
                } catch (Exception e) {
                    throw new java.io.IOException("Test forwarding proxy failed", e);
                } finally { exchange.close(); }
            });
            proxy.start();
            try {
                var proxiedConfig = new ArtifactKeeperConfig(URI.create("http://127.0.0.1:" + proxy.getAddress().getPort()),
                        fixture.admin, 100, ArtifactKeeperApiProfile.CURRENT);
                var core = new PactCore(List.of(new ArtifactKeeperBackend("registry",
                        new ArtifactKeeperHttpClient(proxiedConfig), ArtifactKeeperApiProfile.CURRENT)));
                core.restoreAppliedState(previous);
                var desired = state(fixture.principal, Map.of("name", repo), Set.of("read", "write"), "10.20.0.0/16");
                assertThrows(ApplyException.class, () -> core.apply(desired));
                assertTrue(deleted.get(), "The injected failure must follow an actual server-side deletion");
                assertFalse(inject.get(), "The HTTP fault must have been consumed");
                assertEquals(3, events.size(), "One deletion, failed create, and compensating create expected");
                assertTrue(events.get(0).startsWith("DELETE 2"));
                assertEquals("POST 503", events.get(1));
                assertTrue(events.get(2).startsWith("POST 2"));
                var restored = fixture.permissions();
                assertEquals(1, restored.size());
                var grant = restored.getFirst();
                assertNotEquals(snapshot.id(), grant.id(), "Compensation must recreate the deleted grant");
                assertEquals(snapshot.principalId(), grant.principalId());
                assertEquals(snapshot.principalType(), grant.principalType());
                assertEquals(snapshot.targetId(), grant.targetId());
                assertEquals(snapshot.targetType(), grant.targetType());
                assertEquals(snapshot.actions(), grant.actions());
                assertEquals(snapshot.conditions(), grant.conditions());
                // Core must retain the previous logical state after failure and allow a subsequent retry.
                core.apply(desired);
                assertEquals(Set.of("read", "write"), fixture.permissions().getFirst().actions());
            } finally { proxy.stop(0); }
        }
    }

    private static PactState state(String principal, Map<String, String> target, Set<String> actions, String cidr) {
        var attributes = new java.util.HashMap<String, Value>();
        attributes.put("permissions", Value.set(actions.stream().map(Value::string).collect(java.util.stream.Collectors.toSet())));
        if (cidr != null) attributes.put("allowed_cidrs", Value.set(Set.of(Value.string(cidr))));
        return new PactState(Set.of(new Access(principal, new Resource("registry", target), attributes)));
    }

    private static void assertDenied(int status) {
        assertTrue(status == 403 || status == 404, "Expected authorization denial, got HTTP " + status);
    }

    private static HttpResponse<String> request(String url, String token, String method, String path, Object body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(url.replaceAll("/+$", "") + path))
                .timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json");
        return HTTP.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing test variable: " + name);
        return value;
    }

    private static final class Fixture implements AutoCloseable {
        final String url = required("PACT_IT_ARTIFACT_KEEPER_URL").replaceAll("/+$", "");
        final String admin = required("PACT_IT_ARTIFACT_KEEPER_TOKEN");
        final ArtifactKeeperHttpClient client = new ArtifactKeeperHttpClient(new ArtifactKeeperConfig(URI.create(url), admin, 100, ArtifactKeeperApiProfile.CURRENT));
        final ArtifactKeeperBackend backend = new ArtifactKeeperBackend("registry", client, ArtifactKeeperApiProfile.CURRENT);
        final ArrayDeque<String> cleanup = new ArrayDeque<>();
        String principal;

        Fixture() throws Exception {
            assertTrue(permissions().isEmpty(), "Use a dedicated instance without existing permissions");
        }

        List<ArtifactKeeperApiPermission> permissions() {
            try { return client.getPermissions(); }
            catch (io.github.pactproject.artifactkeeper.client.ArtifactKeeperClientException e) {
                throw new AssertionError("Cannot inspect live grants", e);
            }
        }

        JsonNode api(String method, String path, Object body) throws Exception {
            var response = request(url, admin, method, path, body);
            assertTrue(response.statusCode() >= 200 && response.statusCode() < 300,
                    method + " " + path + " returned HTTP " + response.statusCode());
            return response.body().isBlank() ? JSON.nullNode() : JSON.readTree(response.body());
        }

        String project(String name) throws Exception {
            String id = api("POST", "/api/v1/projects", Map.of("key", "pact-it-" + UUID.randomUUID(), "name", name)).path("id").asText();
            cleanup.push("/api/v1/projects/" + id);
            return id;
        }

        String repository(String project) throws Exception {
            String key = "pact-it-boundary-" + UUID.randomUUID();
            var body = new java.util.HashMap<String, Object>(Map.of("key", key, "name", key,
                    "format", "generic", "repo_type", "local", "is_public", false));
            if (project != null) body.put("project_id", project);
            api("POST", "/api/v1/repositories", body);
            cleanup.push("/api/v1/repositories/" + key);
            api("PUT", "/api/v1/repositories/" + key + "/artifacts/app.bin", Map.of("fixture", "content"));
            return key;
        }

        String serviceAccountToken() throws Exception {
            io.github.pactproject.artifactkeeper.api.ArtifactKeeperApiUser account;
            try { account = client.createServiceAccount("pact-it-enforcement-" + UUID.randomUUID()); }
            catch (io.github.pactproject.artifactkeeper.client.ArtifactKeeperClientException e) {
                throw new Exception("Cannot create test service account", e);
            }
            principal = account.username();
            String path = "/api/v1/service-accounts/" + account.id() + "/tokens";
            var response = api("POST", path, Map.of("name", "pact-it-read", "scopes", List.of("read:artifacts"), "expires_in_days", 1));
            cleanup.push(path + "/" + response.path("id").asText());
            String token = response.path("token").asText();
            assertFalse(token.isBlank(), "Server must return an ephemeral account token");
            return token;
        }

        void assertDownload(String repo, String token, boolean allowed) throws Exception {
            var response = request(url, token, "GET", "/api/v1/repositories/" + repo + "/download/app.bin", null);
            if (allowed) {
                assertEquals(200, response.statusCode(), "Expected permitted artifact download");
                assertEquals("{\"fixture\":\"content\"}", response.body());
            } else assertDenied(response.statusCode());
        }

        @Override public void close() throws Exception {
            Exception failure = null;
            try { backend.apply(PactState.empty()); }
            catch (Exception e) { failure = e; }
            while (!cleanup.isEmpty()) {
                try { api("DELETE", cleanup.pop(), null); }
                catch (Exception e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
            }
            if (failure != null) throw failure;
            assertTrue(permissions().isEmpty(), "Fixture grants must be removed");
        }
    }
}
