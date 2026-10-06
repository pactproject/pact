package io.github.pactproject.elasticsearch;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.pactproject.api.Access;
import io.github.pactproject.api.Identity;
import io.github.pactproject.api.PactState;
import io.github.pactproject.api.Resource;
import io.github.pactproject.api.SecretValue;
import io.github.pactproject.api.value.Value;
import io.github.pactproject.elasticsearch.client.ElasticsearchHttpClient;
import io.github.pactproject.elasticsearch.compile.ElasticsearchRoleCompiler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "pact.integration", matches = "true")
class ElasticsearchLiveIntegrationTest {
    @Test
    void createsAndRemovesOnlyItsOwnedSecurityObjects() throws Exception {
        String endpoint = required("PACT_IT_ELASTICSEARCH_ENDPOINT");
        String username = required("PACT_IT_ELASTICSEARCH_USERNAME");
        String password = required("PACT_IT_ELASTICSEARCH_PASSWORD");
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String principal = "pact-it-" + suffix;
        String indexPattern = "pact-it-" + suffix + "-*";
        String initialPassword = "Pact-it-" + suffix + "-Aa1!";
        String rotatedPassword = "Rotated-" + suffix + "-Bb2!";
        ElasticsearchConfig config = new ElasticsearchConfig(
                URI.create(endpoint),
                username,
                password,
                null,
                "ensure"
        );
        ElasticsearchHttpClient client = new ElasticsearchHttpClient(config);
        ElasticsearchBackend backend = new ElasticsearchBackend(
                "elasticsearch-it",
                config,
                client
        );
        PactState desired = new PactState(
                Set.of(new Access(
                        principal,
                        new Resource(
                                "elasticsearch-it",
                                Map.of("index", indexPattern)
                        ),
                        Map.of("permissions", Value.object(Map.of(
                                "indices",
                                Value.set(Set.of(Value.string("read")))
                        )))
                )),
                Set.of(new Identity(
                        "elasticsearch-it",
                        principal,
                        true,
                        "integration/password",
                        "1",
                        new SecretValue(initialPassword)
                ))
        );
        String ownedRole = ElasticsearchRoleCompiler.roleName(
                "elasticsearch-it", principal
        );
        boolean principalWasAbsent = client.getUser(principal) == null;
        assertTrue(principalWasAbsent,
                "Unexpected collision with generated Elasticsearch user");
        try {
            backend.prepare(PactState.empty(), desired).apply();
            JsonNode role = client.getRole(ownedRole);
            JsonNode user = client.getUser(principal);

            assertTrue(role.path("metadata")
                    .path("pact_managed_by").asText().equals("pact"));
            assertEquals(
                    "elasticsearch-it",
                    role.path("metadata").path("pact_backend_id").asText()
            );
            assertEquals(
                    principal,
                    role.path("metadata").path("pact_principal").asText()
            );
            assertEquals(
                    indexPattern,
                    role.path("indices").get(0).path("names").get(0).asText()
            );
            assertTrue(user.path("roles").toString().contains(ownedRole));
            assertEquals(
                    200,
                    authenticate(endpoint, principal, initialPassword)
            );

            PactState rotated = new PactState(
                    desired.accesses(),
                    Set.of(new Identity(
                            "elasticsearch-it",
                            principal,
                            true,
                            "integration/password",
                            "2",
                            new SecretValue(rotatedPassword)
                    ))
            );
            backend.prepare(desired, rotated).apply();
            assertEquals(
                    401,
                    authenticate(endpoint, principal, initialPassword)
            );
            assertEquals(
                    200,
                    authenticate(endpoint, principal, rotatedPassword)
            );
        }
        finally {
            try {
                backend.prepare(desired, PactState.empty()).apply();
            }
            finally {
                JsonNode leftoverRole = client.getRole(ownedRole);
                JsonNode metadata = leftoverRole == null
                        ? null
                        : leftoverRole.get("metadata");
                if (metadata != null
                        && "pact".equals(metadata.path("pact_managed_by")
                        .asText())
                        && "elasticsearch-it".equals(
                        metadata.path("pact_backend_id").asText())
                        && principal.equals(
                        metadata.path("pact_principal").asText())) {
                    client.deleteRole(ownedRole);
                }
                if (principalWasAbsent && client.getUser(principal) != null) {
                    deleteUniqueUser(endpoint, username, password, principal);
                }
            }
        }
    }

    private static void deleteUniqueUser(
            String endpoint,
            String username,
            String password,
            String principal
    ) throws Exception {
        String token = java.util.Base64.getEncoder().encodeToString(
                (username + ":" + password).getBytes(StandardCharsets.UTF_8)
        );
        String base = endpoint.endsWith("/")
                ? endpoint.substring(0, endpoint.length() - 1)
                : endpoint;
        String path = "/_security/user/"
                + URLEncoder.encode(principal, StandardCharsets.UTF_8)
                        .replace("+", "%20");
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(base + path))
                        .header("Authorization", "Basic " + token)
                        .DELETE()
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertTrue(response.statusCode() == 200
                        || response.statusCode() == 404,
                "Test-only cleanup failed with HTTP " + response.statusCode());
    }

    private static int authenticate(
            String endpoint,
            String username,
            String password)
            throws Exception {
        String token = java.util.Base64.getEncoder().encodeToString(
                (username + ":" + password).getBytes(StandardCharsets.UTF_8)
        );
        String base = endpoint.endsWith("/")
                ? endpoint.substring(0, endpoint.length() - 1)
                : endpoint;
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(
                                URI.create(base + "/_security/_authenticate")
                        )
                        .header("Authorization", "Basic " + token)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        ).statusCode();
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Missing integration-test environment variable: " + name
            );
        }
        return value;
    }
}
