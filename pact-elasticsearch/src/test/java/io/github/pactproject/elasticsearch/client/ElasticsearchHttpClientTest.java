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

class ElasticsearchHttpClientTest {
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
                    .concat("\"roles\":[]}}").getBytes(StandardCharsets.UTF_8);
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
            ElasticsearchHttpClient client =
                    new ElasticsearchHttpClient(config);
            JsonNode user = client.getUser("alice x");

            assertEquals("alice x", user.path("username").asText());
            assertEquals(
                    "/base/_security/user/alice%20x",
                    observedPath.get()
            );
            assertEquals("ApiKey encoded-api-key",
                    observedAuthorization.get());
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
            exchange.sendResponseHeaders(404, -1);
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
            ElasticsearchHttpClient client =
                    new ElasticsearchHttpClient(config);
            assertNull(client.getRole("missing"));
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
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            ElasticsearchHttpClient client = new ElasticsearchHttpClient(
                    new ElasticsearchConfig(
                            URI.create("http://127.0.0.1:"
                                    + server.getAddress().getPort()),
                            "pact",
                            "secret",
                            null,
                            "require"
                    )
            );
            client.updatePassword("alice x", "new-secret");

            assertEquals("POST", observedMethod.get());
            assertEquals(
                    "/_security/user/alice%20x/_password",
                    observedPath.get()
            );
            assertTrue(observedBody.get().contains("\"password\""));
            assertTrue(observedBody.get().contains("\"new-secret\""));
        }
        finally {
            server.stop(0);
        }
    }
}
