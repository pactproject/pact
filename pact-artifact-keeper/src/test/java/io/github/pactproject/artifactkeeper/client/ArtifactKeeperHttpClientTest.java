package io.github.pactproject.artifactkeeper.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.pactproject.artifactkeeper.api.ArtifactKeeperApiPermission;
import io.github.pactproject.artifactkeeper.api.ArtifactKeeperApiUser;
import io.github.pactproject.artifactkeeper.ArtifactKeeperApiProfile;
import io.github.pactproject.artifactkeeper.ArtifactKeeperConfig;
import io.github.pactproject.api.value.Value;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ArtifactKeeperHttpClientTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void readsPagedGroupsUnpagedProjectsAndQualifiedArtifactMetadata() throws Exception, ArtifactKeeperClientException {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var artifactUri = new AtomicReference<java.net.URI>();
        server.createContext("/api/v1/groups", exchange -> {
            boolean first = exchange.getRequestURI().getQuery().contains("page=1&");
            byte[] bytes = ("{\"items\":[{\"id\":\"" + (first ? "g1" : "g2")
                    + "\",\"name\":\"" + (first ? "readers" : "writers")
                    + "\"}],\"pagination\":{\"total_pages\":2}}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.createContext("/api/v1/projects", exchange -> {
            byte[] bytes = "{\"items\":[{\"id\":\"p1\",\"key\":\"payments\",\"name\":\"Payments team\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.createContext("/api/v1/repositories/repo/artifacts/", exchange -> {
            artifactUri.set(exchange.getRequestURI());
            byte[] bytes = (exchange.getRequestURI().getPath().endsWith("unversioned.jar")
                    ? "{\"id\":\"artifact-id\"}"
                    : "{\"id\":\"artifact-id\",\"revision\":7,\"version_label\":\"release / 2\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var client = new ArtifactKeeperHttpClient(new ArtifactKeeperConfig(
                    java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "test-token", 1,
                    ArtifactKeeperApiProfile.CURRENT));
            assertEquals(List.of("readers", "writers"), client.getGroups().stream().map(g -> g.name()).toList());
            var project = client.getProjects().getFirst();
            assertEquals("payments", project.key());
            assertEquals("Payments team", project.name());
            assertEquals("artifact-id", client.getArtifactId("repo", "dir/a b+#.jar", "release / 2"));
            assertEquals("/api/v1/repositories/repo/artifacts/dir/a%20b%2B%23.jar", artifactUri.get().getRawPath());
            assertEquals("version=release / 2", java.net.URLDecoder.decode(artifactUri.get().getRawQuery(), StandardCharsets.UTF_8));
            assertThrows(ArtifactKeeperClientException.class, () -> client.getArtifactId("repo", "unversioned.jar", "7"),
                    "A server that ignored the revision selector must not silently grant HEAD");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void roundTripsTypedPermissionWithConditions() throws Exception, ArtifactKeeperClientException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<JsonNode> posted = new AtomicReference<>();
        server.createContext("/api/v1/permissions", exchange -> {
            JsonNode response;
            if (exchange.getRequestMethod().equals("POST")) {
                posted.set(MAPPER.readTree(exchange.getRequestBody()));
                response = ((com.fasterxml.jackson.databind.node.ObjectNode) posted.get()).deepCopy()
                        .put("id", "permission");
            } else {
                response = MAPPER.createObjectNode().set("items", MAPPER.createArrayNode().add(
                        ((com.fasterxml.jackson.databind.node.ObjectNode) posted.get()).deepCopy()
                                .put("id", "permission")));
                ((com.fasterxml.jackson.databind.node.ObjectNode) response).set("pagination",
                        MAPPER.createObjectNode().put("total_pages", 1));
            }
            byte[] body = MAPPER.writeValueAsBytes(response);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var client = new ArtifactKeeperHttpClient(new ArtifactKeeperConfig(
                    java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    "test-token", 100, ArtifactKeeperApiProfile.CURRENT));
            var conditions = Map.<String, Value>of("allowed_cidrs",
                    Value.set(Set.of(Value.string("10.0.0.0/8"))));
            var created = client.createPermission("group", "principal", "artifact", "target", Set.of("read"), conditions);
            assertEquals("artifact", posted.get().path("target_type").asText());
            assertEquals("target", posted.get().path("target_id").asText());
            assertEquals(MAPPER.readTree("{\"allowed_cidrs\":[\"10.0.0.0/8\"]}"), posted.get().path("conditions"));
            assertEquals(conditions, created.conditions());
            assertEquals(List.of(created), client.getPermissions());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void readsAccountTypesAndCreatesAccountWithCurrentJsonContract()
            throws Exception, ArtifactKeeperClientException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<JsonNode> requestBody = new AtomicReference<>();
        server.createContext("/api/v1/users", exchange -> {
            String users = """
                    {"items": [
                      {"id":"human","username":"svc-human","is_service_account":false},
                      {"id":"machine","username":"imported-bot","is_service_account":true}
                    ], "pagination":{"total_pages":1}}
                    """;
            byte[] body = users.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/api/v1/service-accounts", exchange -> {
            if (!"POST".equals(exchange.getRequestMethod())) {
                // Account discovery must use /users metadata, with no legacy fallback reads.
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }
            requestBody.set(MAPPER.readTree(exchange.getRequestBody().readAllBytes()));
            byte[] body = """
                    {"id":"created","username":"svc-build","display_name":null,"is_active":true}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var client = new ArtifactKeeperHttpClient(new ArtifactKeeperConfig(
                    java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    "test-token", 100));
            assertEquals(List.of(
                    new ArtifactKeeperApiUser("human", "svc-human", false),
                    new ArtifactKeeperApiUser("machine", "imported-bot", true)), client.getUsers());
            var created = client.createServiceAccount("build");
            assertEquals("svc-build", created.username());
            assertTrue(created.serviceAccount(), "create response has no is_service_account field");
            assertEquals(MAPPER.readTree("{\"name\":\"build\"}"), requestBody.get());
        }
        finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsMissingAccountMetadataBeforeReconciliation()
            throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/users", exchange -> {
            byte[] body = """
                    {"items":[{"id":"machine","username":"svc-build"}],"pagination":{"total_pages":1}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var client = new ArtifactKeeperHttpClient(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "test-token");
            var error = assertThrows(ArtifactKeeperClientException.class, client::getUsers);
            assertTrue(error.getMessage().contains("is_service_account"));
        }
        finally {
            server.stop(0);
        }
    }

    @Test
    void readsAndCreatesCurrentPermissionSchema()
            throws Exception, ArtifactKeeperClientException {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> query = new AtomicReference<>();
        AtomicReference<JsonNode> createBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0
        );
        server.createContext("/api/v1/permissions", exchange -> {
            method.set(exchange.getRequestMethod());
            query.set(exchange.getRequestURI().getRawQuery());
            byte[] response;
            if ("GET".equals(exchange.getRequestMethod())) {
                response = """
                        {
                          "items": [{
                            "id": "permission-id",
                            "principal_type": "user",
                            "principal_id": "11111111-1111-1111-1111-111111111111",
                            "principal_name": null,
                            "target_type": "repository",
                            "target_id": "22222222-2222-2222-2222-222222222222",
                            "target_name": null,
                            "actions": ["read"]
                          }],
                          "pagination": {
                            "page": 1,
                            "per_page": 10,
                            "total": 1,
                            "total_pages": 1
                          }
                        }
                        """.getBytes(StandardCharsets.UTF_8);
            }
            else {
                createBody.set(MAPPER.readTree(
                        exchange.getRequestBody().readAllBytes()
                ));
                response = """
                        {
                          "id": "created-permission",
                          "principal_type": "user",
                          "principal_id": "11111111-1111-1111-1111-111111111111",
                          "principal_name": null,
                          "target_type": "repository",
                          "target_id": "22222222-2222-2222-2222-222222222222",
                          "target_name": null,
                          "actions": ["read", "write"],
                          "created_at": "2026-10-06T10:00:00Z",
                          "updated_at": "2026-10-06T10:00:00Z"
                        }
                        """.getBytes(StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().add(
                    "Content-Type",
                    "application/json"
            );
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            ArtifactKeeperHttpClient client = new ArtifactKeeperHttpClient(
                    java.net.http.HttpClient.newHttpClient(),
                    MAPPER,
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "test-token",
                    10
            );

            ArtifactKeeperApiPermission listed =
                    client.getPermissions().getFirst();
            assertEquals("GET", method.get());
            assertEquals("page=1&per_page=10", query.get());
            assertEquals("permission-id", listed.id());
            assertEquals("user", listed.principalType());
            assertEquals(
                    "11111111-1111-1111-1111-111111111111",
                    listed.principalId()
            );
            assertEquals("repository", listed.targetType());
            assertEquals(
                    "22222222-2222-2222-2222-222222222222",
                    listed.targetId()
            );
            assertEquals(Set.of("read"), listed.actions());

            ArtifactKeeperApiPermission created = client.createPermission(
                    "user",
                    listed.principalId(),
                    "repository",
                    listed.targetId(),
                    Set.of("read", "write")
            );
            assertEquals("POST", method.get());
            assertEquals("created-permission", created.id());
            assertEquals(Set.of("read", "write"), created.actions());
            assertEquals(5, createBody.get().size());
            assertEquals("user", createBody.get()
                    .path("principal_type").asText());
            assertEquals("repository", createBody.get()
                    .path("target_type").asText());
            assertFalse(createBody.get().has("principalType"));
            assertFalse(createBody.get().has("targetType"));
            assertEquals(
                    Set.of("read", "write"),
                    MAPPER.convertValue(
                            createBody.get().path("actions"),
                            MAPPER.getTypeFactory()
                                    .constructCollectionType(Set.class, String.class)
                    )
            );
        }
        finally {
            server.stop(0);
        }
    }
}
