import io.github.pactproject.app.PactApplication;
import io.github.pactproject.api.DiagnosticSanitizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.DriverManager;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Runs apply and cleanup with the same core, retaining its previous state. */
class PactE2E {
    public static void main(String[] args) throws Exception {
        Path template = Path.of(args[0]);
        Path desired = Path.of(args[1]);
        Path empty = Path.of(args[2]);
        Path plugins = Path.of(args[3]);
        Path directory = Files.createTempDirectory("pact-e2e-");
        Path state = directory.resolve("state.yaml");
        Path config = directory.resolve("config.yaml");
        try (var elasticsearch = new ElasticsearchFixture()) {
            String source = Files.readString(template);
            if (!source.contains(desired.toString())) {
                throw new IllegalArgumentException("E2E config does not reference the desired-state fixture");
            }
            Files.writeString(config, source.replace(desired.toString(), state.toString()));
            elasticsearch.create();
            String declaration = Files.readString(desired);
            Files.writeString(state, elasticsearch.declaration(declaration, "read"));
            elasticsearch.assertRevoked();
            try (PactApplication application = PactApplication.create(config, plugins)) {
                try {
                    application.run();
                    elasticsearch.assertReadOnly();
                    Files.writeString(state, elasticsearch.declaration(declaration, "read, write"));
                    application.run();
                    elasticsearch.assertReadWrite();
                }
                finally {
                    Files.copy(empty, state, StandardCopyOption.REPLACE_EXISTING);
                    application.run();
                }
            }
            elasticsearch.assertRevoked();
            System.out.println("Elasticsearch filesystem E2E: read, write update, scope boundary and revocation verified");
            try (var connection = DriverManager.getConnection(
                    "jdbc:postgresql://pact-postgres:5432/postgres",
                    System.getenv("PACT_POSTGRES_USER"), System.getenv("PACT_POSTGRES_PASSWORD"));
                 var statement = connection.createStatement();
                 var rows = statement.executeQuery("""
                         SELECT count(*) FROM pg_database d
                         CROSS JOIN LATERAL aclexplode(coalesce(d.datacl, acldefault('d', d.datdba))) a
                         JOIN pg_roles r ON r.oid = a.grantee
                         WHERE d.datname = 'pact_it' AND r.rolname = 'pact_it_user'
                         """)) {
                rows.next();
                if (rows.getInt(1) != 0) {
                    throw new IllegalStateException("PostgreSQL E2E grants remain after cleanup");
                }
            }
        }
        finally {
            Files.deleteIfExists(config);
            Files.deleteIfExists(state);
            Files.deleteIfExists(directory);
        }
    }

    private static final class ElasticsearchFixture implements AutoCloseable {
        private final ObjectMapper json = new ObjectMapper();
        private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        private final String endpoint = required("PACT_IT_ELASTICSEARCH_ENDPOINT");
        private final String adminPassword = required("PACT_IT_ELASTICSEARCH_PASSWORD");
        private final String adminAuthorization = basic(required("PACT_IT_ELASTICSEARCH_USERNAME"), adminPassword);
        private final String suffix = UUID.randomUUID().toString().replace("-", "");
        private final String user = "pact_e2e_" + suffix;
        private final String password = "PactE2e!" + UUID.randomUUID();
        private final String authorization = basic(user, password);
        private final String index = "pact-e2e-" + suffix;
        private final String outside = index + "-outside";
        private final Set<String> generatedRoles = new HashSet<>();

        void create() throws Exception {
            expect(admin("PUT", "/" + index, "{}"), 200);
            expect(admin("PUT", "/" + outside, "{}"), 200);
            for (String target : new String[] {index, outside}) {
                expect(admin("PUT", "/" + target + "/_doc/1?refresh=true", "{\"message\":\"initial\"}"), 201);
            }
            expect(admin("PUT", "/_security/user/" + user, json.writeValueAsString(
                    Map.of("password", password, "roles", new String[0], "enabled", true))), 200);
        }

        String declaration(String template, String privileges) {
            for (String marker : new String[] {"__ES_USER__", "__ES_INDEX__", "__ES_PRIVILEGES__"}) {
                if (!template.contains(marker)) throw new IllegalArgumentException("Missing E2E fixture marker: " + marker);
            }
            return template.replace("__ES_USER__", user).replace("__ES_INDEX__", index)
                    .replace("__ES_PRIVILEGES__", privileges);
        }

        void assertReadOnly() throws Exception {
            expect(asUser("GET", "/" + index + "/_doc/1", null), 200);
            expect(asUser("GET", "/" + outside + "/_doc/1", null), 403);
            expect(asUser("PUT", "/" + index + "/_doc/2?refresh=true", "{\"message\":\"denied\"}"), 403);
            var assigned = userRoles();
            assigned.forEach(role -> generatedRoles.add(role.asText()));
            if (generatedRoles.size() != 1) throw new IllegalStateException("Expected one generated Elasticsearch role");
        }

        void assertReadWrite() throws Exception {
            expect(asUser("PUT", "/" + index + "/_doc/2?refresh=true", "{\"message\":\"updated\"}"), 201);
            expect(asUser("GET", "/" + index + "/_doc/2", null), 200);
            expect(asUser("GET", "/" + outside + "/_doc/1", null), 403);
            var assigned = new HashSet<String>();
            userRoles().forEach(role -> assigned.add(role.asText()));
            if (!assigned.equals(generatedRoles)) throw new IllegalStateException("Privilege update changed generated role identity");
        }

        void assertRevoked() throws Exception {
            expect(asUser("GET", "/" + index + "/_doc/1", null), 403);
            expect(asUser("PUT", "/" + index + "/_doc/3?refresh=true", "{\"message\":\"denied\"}"), 403);
            if (!userRoles().isEmpty()) throw new IllegalStateException("Elasticsearch user assignments remain after cleanup");
            for (String role : generatedRoles) expect(admin("GET", "/_security/role/" + role, null), 404);
            // Revocation must not delete resources or the account itself.
            expect(admin("GET", "/" + index + "/_doc/1", null), 200);
            expect(admin("GET", "/" + outside + "/_doc/1", null), 200);
        }

        private JsonNode userRoles() throws Exception {
            var response = admin("GET", "/_security/user/" + user, null);
            expect(response, 200);
            var roles = json.readTree(response.body()).path(user).path("roles");
            if (!roles.isArray()) throw new IllegalStateException("Elasticsearch user response has no roles array");
            return roles;
        }

        private HttpResponse<String> admin(String method, String path, String body) throws Exception {
            return request(method, path, body, adminAuthorization);
        }

        private HttpResponse<String> asUser(String method, String path, String body) throws Exception {
            return request(method, path, body, authorization);
        }

        private HttpResponse<String> request(String method, String path, String body, String auth) throws Exception {
            var request = HttpRequest.newBuilder(URI.create(endpoint + path)).timeout(Duration.ofSeconds(30))
                    .header("Authorization", auth).header("Content-Type", "application/json")
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }

        private void expect(HttpResponse<String> response, int... statuses) {
            for (int status : statuses) if (response.statusCode() == status) return;
            throw new IllegalStateException("Elasticsearch " + response.request().method() + " "
                    + response.request().uri().getPath() + " returned HTTP " + response.statusCode() + ": "
                    + DiagnosticSanitizer.redact(response.body(), adminPassword, password, adminAuthorization, authorization));
        }

        @Override public void close() throws Exception {
            Exception failure = null;
            var paths = new java.util.ArrayList<String>();
            paths.add("/_security/user/" + user);
            generatedRoles.forEach(role -> paths.add("/_security/role/" + role));
            paths.add("/" + index);
            paths.add("/" + outside);
            for (String path : paths) {
                try { expect(admin("DELETE", path, null), 200, 404); }
                catch (Exception error) {
                    if (failure == null) failure = error;
                    else failure.addSuppressed(error);
                }
            }
            http.close();
            if (failure != null) throw failure;
        }

        private static String basic(String user, String password) {
            return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
        }

        private static String required(String name) {
            String value = System.getenv(name);
            if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing E2E environment variable: " + name);
            return value;
        }
    }
}
