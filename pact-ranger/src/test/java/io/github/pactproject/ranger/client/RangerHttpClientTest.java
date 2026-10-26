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
    void doesNotExposeReflectedCredentialsInHttpOrJsonErrors() throws Exception {
        var status = new java.util.concurrent.atomic.AtomicInteger(401);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String reflected = "alice denied read on probe-resource; reflected-password "
                    + exchange.getRequestHeaders().getFirst("Authorization").substring("Basic ".length());
            byte[] bytes = reflected.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var client = new RangerHttpClient(new RangerConfig(URI.create(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/service"),
                    "sync", "reflected-password", "ozone-cluster", 1));
            for (int response : new int[] {401, 200}) {
                status.set(response);
                var error = assertThrows(io.github.pactproject.ranger.api.RangerClientException.class,
                        () -> client.getPolicies("ozone-cluster", 1));
                var output = new java.io.StringWriter();
                error.printStackTrace(new java.io.PrintWriter(output));
                assertFalse(output.toString().contains("reflected-password"));
                assertFalse(output.toString().contains(Base64.getEncoder().encodeToString(
                        "sync:reflected-password".getBytes(StandardCharsets.UTF_8))));
                if (response == 401) {
                    assertEquals(401, error.statusCode());
                    assertTrue(error.getMessage().contains("alice denied read on probe-resource"));
                }
                else assertTrue(error.getMessage().contains("invalid JSON"));
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void doesNotExposeGeneratedPasswordReflectedByFailedUserCreation() throws Exception {
        var createdPassword = new AtomicReference<String>();
        var mapper = new ObjectMapper();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/service/xusers/users", exchange -> {
            String body = "user not found";
            if (exchange.getRequestMethod().equals("POST")) {
                body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                createdPassword.set(mapper.readTree(body).path("password").asText());
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var client = new RangerHttpClient(new RangerConfig(URI.create(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/service"),
                    "sync", "backend-credential", "ozone-cluster", 1));
            var error = assertThrows(io.github.pactproject.ranger.api.RangerClientException.class,
                    () -> client.ensureUser("alice"));
            assertTrue(createdPassword.get() != null && !createdPassword.get().isBlank());
            assertFalse(error.toString().contains(createdPassword.get()));
            assertTrue(error.getMessage().contains("alice"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void readsEveryPolicyPageAndRejectsMalformedLaterPage() throws Exception {
        var queries = new java.util.ArrayList<String>();
        var malformed = new java.util.concurrent.atomic.AtomicBoolean();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/service/public/v2/api/service/ozone-cluster/policy", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            queries.add(query);
            String body = query.contains("startIndex=0")
                    ? "{\"policies\":[{\"id\":1}],\"totalCount\":2}"
                    : malformed.get() ? "{\"unexpected\":[]}"
                    : "{\"policies\":[{\"id\":2}],\"totalCount\":2}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var client = new RangerHttpClient(new RangerConfig(URI.create(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/service"),
                    "user", "password", "ozone-cluster", 1));
            assertEquals(java.util.List.of(1L, 2L), client.getPolicies("ozone-cluster", 1)
                    .stream().map(policy -> policy.path("id").asLong()).toList());
            assertEquals(java.util.List.of("startIndex=0&pageSize=1", "startIndex=1&pageSize=1"), queries);
            queries.clear();
            malformed.set(true);
            assertThrows(io.github.pactproject.ranger.api.RangerClientException.class,
                    () -> client.getPolicies("ozone-cluster", 1));
            assertEquals(2, queries.size(), "A partial catalog must not be accepted as actual state");
        } finally {
            server.stop(0);
        }
    }

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
