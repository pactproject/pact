package io.github.pactproject.artifactkeeper.client;

import com.sun.net.httpserver.HttpServer;
import io.github.pactproject.api.*;
import io.github.pactproject.api.exception.BackendOperationException;
import io.github.pactproject.api.value.Value;
import io.github.pactproject.artifactkeeper.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class ArtifactKeeperCatalogTest {
    enum Catalog { USERS, REPOSITORIES, PERMISSIONS, GROUPS }
    private static String item(int id) {
        return "{\"id\":\"id-" + id + "\",\"username\":\"user-" + id + "\",\"is_service_account\":false,"
                + "\"key\":\"repo-" + id + "\",\"name\":\"Name " + id + "\",\"principal_type\":\"user\","
                + "\"principal_id\":\"id-1\",\"target_type\":\"repository\",\"target_id\":\"id-1\",\"actions\":[\"read\"]}";
    }
    private static String page(int id, int pages, int total) {
        return "{\"items\":[" + item(id) + "],\"pagination\":{\"total_pages\":" + pages + ",\"total\":" + total + "}}";
    }
    private static List<?> read(ArtifactKeeperHttpClient client, Catalog catalog) throws Exception {
        return switch (catalog) {
            case USERS -> client.getUsers(); case REPOSITORIES -> client.getRepositories();
            case PERMISSIONS -> client.getPermissions(); case GROUPS -> client.getGroups();
        };
    }
    private static ArtifactKeeperHttpClient client(HttpServer server) {
        return new ArtifactKeeperHttpClient(new ArtifactKeeperConfig(URI.create(
                "http://127.0.0.1:" + server.getAddress().getPort()), "test-token", 1));
    }

    @ParameterizedTest @EnumSource(Catalog.class)
    void loadsCompleteCatalogIncludingLaterPages(Catalog catalog) throws Exception {
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            String query = exchange.getRequestURI().getQuery();
            byte[] bytes = page(query.contains("page=1&") ? 1 : 2, 2, 2).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try { assertEquals(2, read(client(server), catalog).size()); assertEquals(2, calls.get()); }
        finally { server.stop(0); }
    }

    static Stream<Arguments> failures() {
        return Arrays.stream(Catalog.values()).flatMap(catalog -> Stream.of("http", "json", "missingItems", "repeat", "changedPages",
                "missingPagination", "totalMismatch", "changedTotal", "emptyPage", "invalidPages", "invalidId").map(mode -> Arguments.of(catalog, mode)));
    }

    @ParameterizedTest @MethodSource("failures")
    void rejectsIncompleteOrInconsistentCatalogInsteadOfReturningPrefix(Catalog catalog, String failure) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var calls = new AtomicInteger();
        server.createContext("/", exchange -> {
            boolean first = calls.incrementAndGet() == 1;
            String body = first ? page(1, failure.equals("emptyPage") ? 3 : 2, failure.equals("totalMismatch") ? 3 : 2)
                    : switch (failure) {
                        case "json" -> "invalid JSON"; case "missingItems" -> "{\"pagination\":{\"total_pages\":2}}";
                        case "repeat" -> page(1, 2, 2); case "changedPages" -> page(2, 3, 2);
                        case "missingPagination" -> "{\"items\":[" + item(2) + "]}";
                        case "invalidPages" -> "{\"items\":[" + item(2) + "],\"pagination\":{\"total_pages\":4294967298,\"total\":2}}";
                        case "invalidId" -> page(2, 2, 2).replace("\"id-2\"", "\"\"");
                        case "totalMismatch" -> page(2, 2, 3); case "changedTotal" -> page(2, 2, 4);
                        case "emptyPage" -> "{\"items\":[],\"pagination\":{\"total_pages\":3,\"total\":2}}";
                        default -> page(2, 2, 2);
                    };
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(!first && failure.equals("http") ? 503 : 200, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try { assertThrows(ArtifactKeeperClientException.class, () -> read(client(server), catalog)); assertEquals(2, calls.get()); }
        finally { server.stop(0); }
    }

    @ParameterizedTest @ValueSource(strings = {"duplicate", "pagination", "total"})
    void unpagedProjectCatalogAlsoRejectsIncompleteData(String mode) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = mode.equals("duplicate") ? "{\"items\":[" + item(1) + "," + item(1) + "]}"
                    : "{\"items\":[" + item(1) + "],\"pagination\":{\"total_pages\":" + (mode.equals("pagination") ? 2 : 1) + ",\"total\":2}}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try { assertThrows(ArtifactKeeperClientException.class, () -> client(server).getProjects()); }
        finally { server.stop(0); }
    }

    @ParameterizedTest @ValueSource(strings = {"users", "repositories", "permissions"})
    void lateCatalogFailureStopsPreparationBeforePermissionsOrAccountWrites(String brokenCatalog) throws Exception {
        var mutations = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            if (!exchange.getRequestMethod().equals("GET")) mutations.incrementAndGet();
            boolean first = exchange.getRequestURI().getQuery().contains("page=1&");
            byte[] bytes = page(first ? 1 : 2, 2, 2).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(!first && exchange.getRequestURI().getPath().endsWith(brokenCatalog) ? 503 : 200, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            var backend = new ArtifactKeeperBackend("packages", client(server));
            var desired = new PactState(Set.of(new Access("svc-build", new Resource("packages", Map.of("repository", "repo-1")),
                    Map.of("actions", Value.set(Set.of(Value.string("read")))))));
            assertThrows(BackendOperationException.class, () -> backend.prepareTransaction(PactState.empty(), desired));
            assertEquals(0, mutations.get());
        } finally { server.stop(0); }
    }
}
