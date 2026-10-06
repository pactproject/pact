package io.github.pactproject.artifactkeeper.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.pactproject.artifactkeeper.api.ArtifactKeeperApiPermission;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ArtifactKeeperHttpClientTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void readsAndCreatesV151PermissionSchema()
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
