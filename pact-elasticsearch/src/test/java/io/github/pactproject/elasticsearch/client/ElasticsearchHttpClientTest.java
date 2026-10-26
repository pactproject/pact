package io.github.pactproject.elasticsearch.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import io.github.pactproject.elasticsearch.ElasticsearchConfig;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ElasticsearchHttpClientTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"{}", "{\"cluster\":[\"monitor\"]}", "{\"index\":[\"read\"]}", "invalid JSON"})
    void incompletePrivilegeCatalogCannotReachAnyBackendWrite(String response) throws Exception {
        var mutations = new java.util.concurrent.atomic.AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            if (!exchange.getRequestMethod().equals("GET")) mutations.incrementAndGet();
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("X-elastic-product", "Elasticsearch");
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        var config = new ElasticsearchConfig(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), null, null, "test-key", "require");
        try (var client = new ElasticsearchHttpClient(config)) {
            var backend = new io.github.pactproject.elasticsearch.ElasticsearchBackend("search", config, client);
            var privileges = io.github.pactproject.api.value.Value.set(java.util.Set.of(io.github.pactproject.api.value.Value.string("monitor")));
            var permissions = io.github.pactproject.api.value.Value.object(java.util.Map.of("cluster", privileges));
            var access = new io.github.pactproject.api.Access("alice",
                    new io.github.pactproject.api.Resource("search", java.util.Map.of("cluster", "*")), java.util.Map.of("permissions", permissions));
            var desired = new io.github.pactproject.api.PactState(java.util.Set.of(access));
            assertThrows(io.github.pactproject.api.exception.BackendException.class,
                    () -> backend.prepareTransaction(io.github.pactproject.api.PactState.empty(), desired));
            assertEquals(0, mutations.get());
        } finally { server.stop(0); }
    }

    @Test
    void doesNotExposeCredentialBearingServerErrors() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = "{\"error\":{\"type\":\"security_exception\",\"reason\":\"alice denied read on logs-*; reflected-secret-token\"},\"status\":401}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("X-elastic-product", "Elasticsearch");
            exchange.sendResponseHeaders(401, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try (var client = new ElasticsearchHttpClient(new ElasticsearchConfig(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()), null, null, "reflected-secret-token", "require"))) {
            var error = assertThrows(io.github.pactproject.elasticsearch.api.ElasticsearchClientException.class, client::getPrivileges);
            assertTrue(error.getMessage().contains("HTTP 401"));
            assertTrue(!error.toString().contains("reflected-secret-token"));
            assertTrue(error.getMessage().contains("alice denied read on logs-*"));
            assertNull(error.getCause(), "Raw SDK exceptions can retain credential-bearing response bodies");
        } finally { server.stop(0); }
    }
    @Test
    void retainsWriteFailureDetailsButMasksThePasswordBeingSet() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String password = new com.fasterxml.jackson.databind.ObjectMapper().readTree(exchange.getRequestBody())
                    .path("password").asText();
            byte[] body = ("{\"error\":{\"type\":\"validation_exception\",\"reason\":\"alice invalid password "
                    + password + "\"},\"status\":400}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("X-elastic-product", "Elasticsearch");
            exchange.sendResponseHeaders(400, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try (var client = new ElasticsearchHttpClient(new ElasticsearchConfig(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()), null, null, "api-key", "require"))) {
            var error = assertThrows(io.github.pactproject.elasticsearch.api.ElasticsearchClientException.class,
                    () -> client.updatePassword("alice", "new-user-secret"));
            assertTrue(error.getMessage().contains("alice"));
            assertTrue(!error.toString().contains("new-user-secret"));
        } finally { server.stop(0); }
    }

    @Test
    void usesApiKeyAndEncodesSecurityApiPathSegments() throws Exception {
        AtomicReference<String> observedPath = new AtomicReference<>();
        AtomicReference<String> observedAuthorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0
        );
        server.createContext("/base/_security/user", exchange -> {
            observedPath.set(exchange.getRequestURI().getRawPath());
            observedAuthorization.set(
                    exchange.getRequestHeaders().getFirst("Authorization")
            );
            byte[] body = "{\"alice x\":{\"username\":\"alice x\","
                    .concat("\"roles\":[],\"enabled\":true,\"metadata\":{}}}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("X-elastic-product", "Elasticsearch");
            exchange.getResponseHeaders().add(
                    "Content-Type", "application/json"
            );
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            ElasticsearchConfig config = new ElasticsearchConfig(
                    URI.create("http://127.0.0.1:" + port + "/base/"),
                    null,
                    null,
                    "encoded-api-key",
                    "require"
            );
            try (ElasticsearchHttpClient client = new ElasticsearchHttpClient(config)) {
            JsonNode user = client.getUser("alice x");

            assertEquals("alice x", user.path("username").asText());
            assertEquals(
                    "/base/_security/user/alice%20x",
                    observedPath.get()
            );
            assertEquals("ApiKey encoded-api-key",
                    observedAuthorization.get());
            }
        }
        finally {
            server.stop(0);
        }
    }

    @Test
    void treatsNotFoundAsMissingObject() throws Exception {
        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0
        );
        server.createContext("/_security/role", exchange -> {
            byte[] body = "{\"error\":{\"type\":\"resource_not_found_exception\",\"reason\":\"missing\"},\"status\":404}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("X-elastic-product", "Elasticsearch");
            exchange.sendResponseHeaders(404, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            ElasticsearchConfig config = new ElasticsearchConfig(
                    URI.create("http://127.0.0.1:"
                            + server.getAddress().getPort()),
                    "pact",
                    "secret",
                    null,
                    "require"
            );
            try (ElasticsearchHttpClient client = new ElasticsearchHttpClient(config)) {
                assertNull(client.getRole("missing"));
            }
        }
        finally {
            server.stop(0);
        }
    }

    @Test
    void updatesPasswordThroughSecurityApi() throws Exception {
        AtomicReference<String> observedMethod = new AtomicReference<>();
        AtomicReference<String> observedPath = new AtomicReference<>();
        AtomicReference<String> observedBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0
        );
        server.createContext("/_security/user", exchange -> {
            observedMethod.set(exchange.getRequestMethod());
            observedPath.set(exchange.getRequestURI().getRawPath());
            observedBody.set(new String(
                    exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8
            ));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("X-elastic-product", "Elasticsearch");
            exchange.sendResponseHeaders(200, 2);
            exchange.getResponseBody().write("{}".getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        server.start();
        try {
            try (ElasticsearchHttpClient client = new ElasticsearchHttpClient(
                    new ElasticsearchConfig(
                            URI.create("http://127.0.0.1:"
                                    + server.getAddress().getPort()),
                            "pact",
                            "secret",
                            null,
                            "require"
                    )
            )) {
            client.updatePassword("alice x", "new-secret");

            assertEquals("PUT", observedMethod.get());
            assertEquals(
                    "/_security/user/alice%20x/_password",
                    observedPath.get()
            );
            assertTrue(observedBody.get().contains("\"password\""));
            assertTrue(observedBody.get().contains("\"new-secret\""));
            }
        }
        finally {
            server.stop(0);
        }
    }
}
