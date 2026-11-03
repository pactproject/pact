package io.github.pactproject.grafana;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import io.github.pactproject.grafana.api.GrafanaClientException;
import io.github.pactproject.grafana.client.GrafanaHttpClient;
import io.github.pactproject.grafana.model.*;
import org.junit.jupiter.api.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.pactproject.grafana.GrafanaCompilerTest.*;

class GrafanaHttpClientTest {
    HttpServer server;
    ExecutorService executor;
    GrafanaHttpClient client;
    final Map<String, Function<HttpExchange, Reply>> routes = new ConcurrentHashMap<>();
    final List<String> requests = new CopyOnWriteArrayList<>();
    final List<String> writes = new CopyOnWriteArrayList<>();
    final String token = "test-credential-unique";
    record Reply(int status, String body) { }

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            try {
                requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
                if (!exchange.getRequestMethod().equals("GET")) writes.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                var route = routes.get(exchange.getRequestURI().getRawPath());
                var reply = route == null ? new Reply(404, "missing route") : route.apply(exchange);
                if (reply.status() == -1) return; // Close after consuming an effective write, losing the response.
                byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(reply.status(), body.length);
                exchange.getResponseBody().write(body);
            } finally { exchange.close(); }
        });
        server.start();
        client = new GrafanaHttpClient(config(2, 1));
        route("/proxy/api/org", "{\"id\":1}");
        route("/proxy/api/org/users", "[{\"orgId\":1,\"userId\":10,\"login\":\"alice\"}]");
    }

    GrafanaConfig config(int pageSize, int timeout) {
        return GrafanaConfig.from(Map.of("endpoint", "http://127.0.0.1:" + server.getAddress().getPort() + "/proxy",
                "token", token, "org-id", "1", "page-size", "" + pageSize, "timeout-seconds", "" + timeout));
    }
    void route(String path, String body) { routes.put(path, ignored -> new Reply(200, body)); }
    @AfterEach void stop() { client.close(); server.stop(0); executor.shutdownNow(); }

    @Test void mapsAuthenticationOrganizationAllSearchPagesParentDetailsAndAclWrites() throws Exception {
        routes.put("/proxy/api/org", exchange -> {
            assertEquals("Bearer " + token, exchange.getRequestHeaders().getFirst("Authorization"));
            return new Reply(200, "{\"id\":1}");
        });
        routes.put("/proxy/api/search", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            if (query.contains("page=2")) return new Reply(200, "[]");
            return new Reply(200, query.contains("dash-folder")
                    ? "[{\"type\":\"dash-folder\",\"uid\":\"root\",\"title\":\"Root\"},{\"type\":\"dash-folder\",\"uid\":\"child\",\"title\":\"Child\"}]"
                    : "[{\"type\":\"dash-db\",\"uid\":\"dash\",\"title\":\"CPU\",\"folderUid\":\"child\"}]");
        });
        route("/proxy/api/folders/root", "{\"uid\":\"root\",\"title\":\"Root\"}");
        route("/proxy/api/folders/child", "{\"uid\":\"child\",\"title\":\"Child\",\"parentUid\":\"root\"}");
        var resource = new GrafanaResource(GrafanaResource.Kind.DASHBOARD, "dash");
        routes.put("/proxy/api/dashboards/uid/dash/permissions", exchange -> new Reply(200, exchange.getRequestMethod().equals("POST")
                ? "{\"message\":\"updated\"}" : "[{\"userId\":10,\"permission\":2},{\"teamId\":5,\"permission\":1},{\"role\":\"Viewer\",\"permission\":1},"
                + "{\"userId\":20,\"permission\":4,\"inherited\":true}]"));
        client.verifyOrganization();
        assertEquals(List.of(new GrafanaUser(10, "alice")), client.users());
        var objects = client.objects();
        assertEquals(3, objects.size());
        assertEquals("root", objects.get(1).parentUid());
        assertEquals(3, client.permissions(resource).size());
        client.replacePermissions(resource, List.of(GrafanaPermission.user(10, 2)));
        assertEquals(List.of("{\"items\":[{\"permission\":2,\"userId\":10}]}"), writes);
        assertTrue(requests.stream().anyMatch(request -> request.contains("type=dash-db&limit=2&page=2")));
    }

    @Test void encodesUidPathSegmentsAndClearsWithAnEmptyItemsArray() throws Exception {
        var uid = "folder /?#";
        var resource = new GrafanaResource(GrafanaResource.Kind.FOLDER, uid);
        route("/proxy/api/folders/folder%20%2F%3F%23/permissions", "{\"message\":\"updated\"}");
        client.replacePermissions(resource, List.of());
        assertEquals(List.of("{\"items\":[]}"), writes);
        assertEquals("POST /proxy/api/folders/folder%20%2F%3F%23/permissions", requests.getFirst());
    }

    @Test void rejectsLaterPageFailuresDuplicatesMalformedRowsAndPrematureShortPagesBeforeWrites() {
        String row = "{\"type\":\"dash-db\",\"uid\":\"d\",\"title\":\"D\"}";
        for (var later : List.of(new Reply(503, "catalog unavailable"), new Reply(200, "[" + row + "]"),
                new Reply(200, "{}"), new Reply(200, "[{\"title\":\"D\"}]"))) {
            routes.put("/proxy/api/search", exchange -> {
                String query = exchange.getRequestURI().getQuery();
                if (query.contains("dash-folder")) return new Reply(200, "[]");
                return query.contains("page=1") ? new Reply(200, "[" + row + "]") : later;
            });
            assertThrows(GrafanaClientException.class, client::objects);
        }
        for (var first : List.of("{}", "null", "[{}]", "[{\"type\":\"dash-db\",\"uid\":\"x\",\"title\":\"X\",\"folderId\":1}]",
                "[" + row + "," + row + "]", "[{\"type\":\"dash-db\",\"uid\":\"x\",\"title\":12}]")) {
            routes.put("/proxy/api/search", exchange -> new Reply(200, exchange.getRequestURI().getQuery().contains("dash-folder") ? "[]" : first));
            assertThrows(GrafanaClientException.class, client::objects);
        }
        assertTrue(writes.isEmpty());
    }

    @Test void acceptsFullPagesAndRejectsOversizedPagesAndIncompleteFolderDetails() throws Exception {
        try (var small = new GrafanaHttpClient(config(1, 1))) {
            routes.put("/proxy/api/search", exchange -> {
                String query = exchange.getRequestURI().getQuery();
                if (query.contains("dash-folder") || query.contains("page=3")) return new Reply(200, "[]");
                String uid = query.contains("page=1") ? "first" : "second";
                return new Reply(200, "[{\"type\":\"dash-db\",\"uid\":\"" + uid + "\",\"title\":\"D\"}]");
            });
            assertEquals(2, small.objects().size());
            routes.put("/proxy/api/search", exchange -> new Reply(200, "[{\"uid\":\"a\"},{\"uid\":\"b\"}]"));
            assertThrows(GrafanaClientException.class, small::objects);
        }
        routes.put("/proxy/api/search", exchange -> new Reply(200, "[{\"type\":\"dash-folder\",\"uid\":\"f\",\"title\":\"F\"}]"));
        for (String detail : List.of("{}", "{\"uid\":\"other\",\"title\":\"F\"}", "{\"uid\":\"f\",\"title\":\"F\",\"parentUid\":1}")) {
            route("/proxy/api/folders/f", detail);
            assertThrows(GrafanaClientException.class, client::objects);
        }
    }

    @Test void validatesUnpagedUserAndAclCatalogsWithoutSwallowingMalformedEntries() {
        for (String body : List.of("{}", "null", "[] {}", "[{}]", "[{\"orgId\":2,\"userId\":10,\"login\":\"alice\"}]",
                "[{\"orgId\":1,\"userId\":\"10\",\"login\":\"alice\"}]",
                "[{\"orgId\":1,\"userId\":10,\"login\":\"alice\"},{\"orgId\":1,\"userId\":10,\"login\":\"bob\"}]")) {
            route("/proxy/api/org/users", body);
            assertThrows(GrafanaClientException.class, client::users);
        }
        var resource = new GrafanaResource(GrafanaResource.Kind.FOLDER, "f");
        for (String body : List.of("{}", "[{}]", "[{\"inherited\":true}]", "[{\"userId\":10,\"permission\":1,\"uid\":\"another-target\"}]", "[{\"userId\":10,\"permission\":3}]", "[{\"userId\":10,\"teamId\":2,\"permission\":1}]",
                "[{\"role\":\"Custom\",\"permission\":1}]", "[{\"userId\":10,\"permission\":1,\"inherited\":\"true\"}]",
                "[{\"userId\":-1,\"permission\":1}]", "[{\"userId\":10,\"permission\":1},{\"userId\":10,\"permission\":\"2\"}]")) {
            route("/proxy/api/folders/f/permissions", body);
            assertThrows(GrafanaClientException.class, () -> client.permissions(resource));
        }
        assertTrue(writes.isEmpty());
    }

    @Test void redactsReflectedSecretsAndParserErrorsWhilePreservingOperationalDetails() {
        for (int status : List.of(401, 403, 429, 500, 302)) {
            routes.put("/proxy/api/org", exchange -> new Reply(status, "folder f: " + token + " Authorization: Bearer " + token + " password=unknown-secret"));
            var failure = assertThrows(GrafanaClientException.class, client::verifyOrganization);
            assertTrue(failure.getMessage().contains("folder f"));
            assertTrue(failure.getMessage().contains("HTTP " + status));
            assertFalse(failure.getMessage().contains(token));
            assertFalse(failure.getMessage().contains("unknown-secret"));
            assertNull(failure.getCause());
        }
        route("/proxy/api/org", "{\"id\":1,\"id\":1,\"token\":\"" + token + "\"}");
        assertFalse(assertThrows(GrafanaClientException.class, client::verifyOrganization).getMessage().contains(token));
        route("/proxy/api/org", "{\"id\":2}");
        assertThrows(GrafanaClientException.class, client::verifyOrganization);
    }

    @Test void doesNotRetryLostWriteResponsesAndCloseDoesNotWrite() {
        var resource = new GrafanaResource(GrafanaResource.Kind.FOLDER, "f");
        routes.put("/proxy/api/folders/f/permissions", exchange -> new Reply(-1, ""));
        assertThrows(GrafanaClientException.class, () -> client.replacePermissions(resource, List.of(GrafanaPermission.user(10, 1))));
        assertEquals(1, writes.size());
        client.close();
        assertThrows(GrafanaClientException.class, client::verifyOrganization);
        assertEquals(1, writes.size());
    }

    @Test void boundsRequestTimeoutAndPreservesInterruption() {
        routes.put("/proxy/api/org", exchange -> {
            try { Thread.sleep(1800); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return new Reply(200, "{\"id\":1}");
        });
        assertThrows(GrafanaClientException.class, client::verifyOrganization);
        Thread.currentThread().interrupt();
        try {
            assertThrows(GrafanaClientException.class, client::verifyOrganization);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    @Test void malformedLaterAclPreventsEarlierAclWriteThroughBackend() {
        routes.put("/proxy/api/search", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            return new Reply(200, query.contains("dash-folder") || query.contains("page=2") ? "[]"
                    : "[{\"type\":\"dash-db\",\"uid\":\"a\",\"title\":\"A\"},{\"type\":\"dash-db\",\"uid\":\"z\",\"title\":\"Z\"}]");
        });
        route("/proxy/api/dashboards/uid/a/permissions", "[]");
        route("/proxy/api/dashboards/uid/z/permissions", "[{\"unexpected\":true}]");
        var desired = state(access("alice", Map.of("dashboardUid", "a"), "View"), access("alice", Map.of("dashboardUid", "z"), "View"));
        assertThrows(io.github.pactproject.api.exception.BackendOperationException.class, () -> new GrafanaBackend("monitoring", client).apply(desired));
        assertTrue(writes.isEmpty());
    }
}
