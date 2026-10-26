package io.github.pactproject.ranger.client;

import com.sun.net.httpserver.HttpServer;
import io.github.pactproject.api.PactState;
import io.github.pactproject.api.exception.BackendException;
import io.github.pactproject.ranger.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class RangerCatalogTest {
    @ParameterizedTest @ValueSource(strings = {"{}", "{\"resources\":[],\"accessTypes\":[{\"name\":\"read\"}]}",
            "{\"resources\":[{\"name\":\"volume\"},{\"name\":\"volume\"}],\"accessTypes\":[{\"name\":\"read\"}]}",
            "{\"resources\":[{\"name\":\"volume\"}],\"accessTypes\":[{\"name\":\"read\"},{\"name\":\"read\"}]}"})
    void malformedServiceDefinitionCannotReachPolicyWrites(String definition) throws Exception {
        var mutations = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            if (!exchange.getRequestMethod().equals("GET")) mutations.incrementAndGet();
            byte[] bytes = (exchange.getRequestURI().getPath().contains("definitions")
                    ? definition : "{\"name\":\"service\",\"type\":\"ozone\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            var config = config(server);
            assertThrows(BackendException.class, () -> new RangerBackend("warehouse", config, new RangerHttpClient(config))
                    .prepareTransaction(PactState.empty(), PactState.empty()));
            assertEquals(0, mutations.get());
        } finally { server.stop(0); }
    }

    @ParameterizedTest @ValueSource(strings = {"array", "policies", "list"})
    void readsCompleteCatalogAcrossEverySupportedEnvelope(String envelope) throws Exception {
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            int page = calls.incrementAndGet();
            String array = page <= 2 ? "[{\"id\":" + page + ",\"name\":\"policy-" + page + "\"}]" : "[]";
            String body = envelope.equals("array") ? array : "{\"" + envelope + "\":" + array + ",\"totalCount\":2}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            // With a total, short pages must not hide later items even if the server caps page size.
            int pageSize = envelope.equals("array") ? 1 : 20;
            assertEquals(2, new RangerHttpClient(config(server)).getPolicies("service", pageSize).size());
            assertEquals(envelope.equals("array") ? 3 : 2, calls.get());
        } finally { server.stop(0); }
    }

    @ParameterizedTest @ValueSource(strings = {"http", "json", "repeat", "empty", "changedTotal", "missingId"})
    void incompleteActualCatalogPreventsEveryBackendWrite(String failure) throws Exception {
        var pages = new AtomicInteger();
        var mutations = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            if (!exchange.getRequestMethod().equals("GET")) mutations.incrementAndGet();
            boolean policies = exchange.getRequestURI().getPath().endsWith("/policy");
            boolean first = policies && pages.incrementAndGet() == 1;
            String body;
            if (!policies) body = exchange.getRequestURI().getPath().contains("definitions")
                    ? "{\"name\":\"ozone\",\"resources\":[{\"name\":\"volume\"}],\"accessTypes\":[{\"name\":\"read\"}]}"
                    : "{\"name\":\"service\",\"type\":\"ozone\"}";
            else if (first) body = "{\"policies\":[{\"id\":1,\"name\":\"policy-1\"}],\"totalCount\":2}";
            else body = switch (failure) {
                case "json" -> "invalid JSON";
                case "repeat" -> "{\"policies\":[{\"id\":1}],\"totalCount\":2}";
                case "empty" -> "{\"policies\":[],\"totalCount\":2}";
                case "changedTotal" -> "{\"policies\":[{\"id\":2}],\"totalCount\":3}";
                case "missingId" -> "{\"policies\":[{}],\"totalCount\":2}";
                default -> "{}";
            };
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(policies && !first && failure.equals("http") ? 503 : 200, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            var config = config(server);
            var backend = new RangerBackend("warehouse", config, new RangerHttpClient(config));
            assertThrows(BackendException.class, () -> backend.prepareTransaction(PactState.empty(), PactState.empty()));
            assertEquals(2, pages.get());
            assertEquals(0, mutations.get());
        } finally { server.stop(0); }
    }

    private static RangerConfig config(HttpServer server) {
        return new RangerConfig(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/service"),
                "sync", "test-password", "service", 1);
    }
}
