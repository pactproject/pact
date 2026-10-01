package io.github.pactproject.ranger.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.pactproject.ranger.RangerConfig;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RangerHttpClientTest {
    @Test
    void sendsBasicAuthAndUsesConfiguredServiceEndpoint() throws Exception {
        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0),
                0
        );
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> requestPath = new AtomicReference<>();
        server.createContext(
                "/service/public/v2/api/service/name/ozone-cluster",
                exchange -> {
                    authorization.set(
                            exchange.getRequestHeaders()
                                    .getFirst("Authorization")
                    );
                    requestPath.set(exchange.getRequestURI().getPath());
                    byte[] body = """
                            {"name":"ozone-cluster","type":"ozone"}
                            """.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add(
                            "Content-Type",
                            "application/json"
                    );
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                }
        );
        server.createContext(
                "/service/plugins/definitions/name/ozone",
                exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestPath.set(exchange.getRequestURI().getPath());
            byte[] body = """
                    {"name":"ozone","resources":[],"accessTypes":[]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            RangerConfig config = new RangerConfig(
                    URI.create("http://127.0.0.1:"
                            + server.getAddress().getPort() + "/service"),
                    "pact-user",
                    "pact-password",
                    "ozone-cluster",
                    100
            );
            RangerHttpClient client = new RangerHttpClient(
                    java.net.http.HttpClient.newHttpClient(),
                    new ObjectMapper(),
                    config
            );

            assertEquals(
                    "ozone",
                    client.getServiceType("ozone-cluster")
            );
            assertEquals(
                    "/service/public/v2/api/service/name/ozone-cluster",
                    requestPath.get()
            );
            assertEquals(
                    "ozone",
                    client.getServiceDefinition("ozone").path("name").asText()
            );
            assertEquals(
                    "/service/plugins/definitions/name/ozone",
                    requestPath.get()
            );
            assertEquals(
                    "Basic " + Base64.getEncoder().encodeToString(
                            "pact-user:pact-password"
                                    .getBytes(StandardCharsets.UTF_8)
                    ),
                    authorization.get()
            );
        }
        finally {
            server.stop(0);
        }
    }

    @Test
    void createsMissingUserWithStrongRandomPassword() throws Exception {
        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0),
                0
        );
        AtomicReference<String> createdUser = new AtomicReference<>();
        server.createContext(
                "/service/xusers/users/userName/alice",
                exchange -> {
                    exchange.sendResponseHeaders(400, -1);
                    exchange.close();
                }
        );
        server.createContext("/service/xusers/users", exchange -> {
            createdUser.set(new String(
                    exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8
            ));
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            RangerHttpClient client = new RangerHttpClient(
                    java.net.http.HttpClient.newHttpClient(),
                    new ObjectMapper(),
                    new RangerConfig(
                            URI.create("http://127.0.0.1:"
                                    + server.getAddress().getPort() + "/service"),
                            "pact-user",
                            "pact-password",
                            "ozone-cluster",
                            100
                    )
            );
            client.ensureUser("alice");

            var user = new ObjectMapper().readTree(createdUser.get());
            String password = user.path("password").asText();
            assertEquals("alice", user.path("name").asText());
            assertEquals(1, user.path("status").asInt());
            assertEquals(1, user.path("isVisible").asInt());
            assertEquals(24, password.length());
            assertTrue(password.chars().anyMatch(Character::isUpperCase));
            assertTrue(password.chars().anyMatch(Character::isLowerCase));
            assertTrue(password.chars().anyMatch(Character::isDigit));
            assertTrue(password.chars().anyMatch(ch ->
                    PASSWORD_SPECIAL.indexOf(ch) >= 0
            ));
            assertFalse(user.has("firstName"));
        }
        finally {
            server.stop(0);
        }
    }

    private static final String PASSWORD_SPECIAL = "!@#$%^&*_-+=";
}
