package io.github.pactproject.elasticsearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pactproject.api.*;
import io.github.pactproject.api.exception.BackendOperationException;
import io.github.pactproject.api.exception.ValidationException;
import io.github.pactproject.api.value.Value;
import io.github.pactproject.elasticsearch.api.*;
import io.github.pactproject.elasticsearch.client.ElasticsearchHttpClient;
import io.github.pactproject.elasticsearch.compile.ElasticsearchRoleCompiler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "pact.integration", matches = "true")
class ElasticsearchLiveIntegrationTest {
    private static final String BACKEND = "elasticsearch-it";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void enforcesIndexAndClusterPrivilegesAndRotatesOnlyExplicitSecrets() throws Exception {
        try (var f = new Fixture(ElasticsearchReconciliationMode.MANAGED)) {
            String user = f.user("reader"), index = f.index("logs"), other = f.index("private");
            f.admin("PUT", "/" + index + "/_doc/1?refresh=true", "{\"message\":\"visible\"}", 201);
            f.admin("PUT", "/" + other + "/_doc/1?refresh=true", "{\"message\":\"private\"}", 201);
            var initial = new PactState(Set.of(indices(user, index, "read"), cluster(user, "monitor")),
                    Set.of(identity(user, "1", "Initial-it-Aa1!")));
            f.backend.prepareTransaction(PactState.empty(), initial).apply();
            var role = f.client.getRole(roleName(user));
            assertEquals(BACKEND, role.path("metadata").path("pact_backend_id").asText());
            assertEquals(user, role.path("metadata").path("pact_principal").asText());
            var observed = new ObservedClient(f.client);
            observed.failAfterUserWrite = false;
            new ElasticsearchBackend(BACKEND, f.config, observed).prepareTransaction(initial.withoutSecrets(), initial).apply();
            assertEquals(0, observed.writes, "Server defaults must not trigger repeated role/user writes");
            f.as(user, "Initial-it-Aa1!", "GET", "/" + index + "/_search", null, 200);
            f.as(user, "Initial-it-Aa1!", "POST", "/" + index + "/_doc", "{}", 403);
            f.as(user, "Initial-it-Aa1!", "GET", "/" + other + "/_search", null, 403);
            f.as(user, "Initial-it-Aa1!", "GET", "/_cluster/health", null, 200);
            f.as(user, "Initial-it-Aa1!", "PUT", "/_cluster/settings", "{\"persistent\":{\"cluster.max_shards_per_node\":1000}}", 403);
            var rotated = new PactState(initial.accesses(), Set.of(identity(user, "2", "Rotated-it-Bb2!")));
            f.backend.prepareTransaction(initial.withoutSecrets(), rotated).apply();
            f.as(user, "Initial-it-Aa1!", "GET", "/_security/_authenticate", null, 401);
            f.as(user, "Rotated-it-Bb2!", "GET", "/_security/_authenticate", null, 200);
            f.backend.prepareTransaction(rotated.withoutSecrets(), PactState.empty()).apply();
            assertNull(f.client.getRole(roleName(user)));
            assertNotNull(f.client.getUser(user));
            f.as(user, "Rotated-it-Bb2!", "GET", "/" + index + "/_search", null, 403);
        }
    }

    @Test
    void createsIdentityOnlyAndPreservesOperatorPasswordAcrossReconciliation() throws Exception {
        try (var f = new Fixture(ElasticsearchReconciliationMode.MANAGED)) {
            String user = f.user("operator");
            var state = new PactState(Set.of(), Set.of(new Identity(BACKEND, user, true, null, null, null)));
            f.backend.prepareTransaction(PactState.empty(), state).apply();
            assertEquals(Set.of(), roles(f.client.getUser(user)));
            assertNull(f.client.getRole(roleName(user)));
            f.client.updatePassword(user, "Manual-it-Cc3!");
            f.backend.prepareTransaction(state, state).apply();
            f.as(user, "Manual-it-Cc3!", "GET", "/_security/_authenticate", null, 200);
            f.backend.prepareTransaction(state, PactState.empty()).apply();
            f.as(user, "Manual-it-Cc3!", "GET", "/_security/_authenticate", null, 200);
        }
    }

    @Test
    void managedModeRevokesDeclaredRolesAndPreservesForeignRolesAndSharedDefinitions() throws Exception {
        try (var f = new Fixture(ElasticsearchReconciliationMode.MANAGED)) {
            String user = f.createUser("managed"), external = f.role("external"), mixed = f.role("mixed");
            f.client.putUser(user, JSON.readTree("{\"roles\":[\"" + external + "\"],\"metadata\":{\"note\":\"keep\"}}"));
            JsonNode shared = f.client.getRole(mixed);
            var desired = new PactState(Set.of(cluster(user, null, mixed, "monitoring_user")));
            f.backend.prepareTransaction(PactState.empty(), desired).apply();
            assertEquals(Set.of(external, mixed, "monitoring_user"), roles(f.client.getUser(user)));
            assertNull(f.client.getRole(roleName(user)));
            assertEquals(shared, f.client.getRole(mixed));
            f.backend.prepareTransaction(desired, PactState.empty()).apply();
            assertEquals(Set.of(external), roles(f.client.getUser(user)));
            assertEquals("keep", f.client.getUser(user).path("metadata").path("note").asText());
            assertEquals(shared, f.client.getRole(mixed));
        }
    }

    @Test
    void authoritativeModeRemovesMixedRoleAssignmentsOnlyInsideDeclaredUserScope() throws Exception {
        try (var f = new Fixture(ElasticsearchReconciliationMode.AUTHORITATIVE)) {
            String user = f.createUser("authoritative"), other = f.createUser("unmanaged"), mixed = f.role("mixed");
            JsonNode assigned = JSON.readTree("{\"roles\":[\"" + mixed + "\"]}");
            f.client.putUser(user, assigned);
            f.client.putUser(other, assigned);
            JsonNode definition = f.client.getRole(mixed);
            var desired = new PactState(Set.of(cluster(user, "monitor", "monitoring_user")));
            var tx = f.backend.prepareTransaction(PactState.empty(), desired);
            tx.apply();
            assertEquals(Set.of(roleName(user), "monitoring_user"), roles(f.client.getUser(user)));
            assertEquals(Set.of(mixed), roles(f.client.getUser(other)));
            assertEquals(definition, f.client.getRole(mixed));
            tx.rollback();
            assertEquals(Set.of(), roles(f.client.getUser(user)));
            assertEquals(Set.of(mixed), roles(f.client.getUser(other)));
            assertNull(f.client.getRole(roleName(user)));
        }
    }

    @Test
    void invalidReferencesAndReservedUsersFailBeforeCreatingAccounts() throws Exception {
        try (var f = new Fixture(ElasticsearchReconciliationMode.MANAGED)) {
            String user = f.user("must-not-exist");
            var ensure = new Identity(BACKEND, user, true, null, null, null);
            for (Access bad : List.of(cluster(user, null, f.prefix + "missing-role"),
                    cluster(user, "not_a_privilege"), indices(user, "*", "not_a_privilege"))) {
                assertThrows(ValidationException.class, () -> f.backend.prepareTransaction(PactState.empty(),
                        new PactState(Set.of(bad), Set.of(ensure))));
                assertNull(f.client.getUser(user));
                assertNull(f.client.getRole(roleName(user)));
            }
            assertThrows(ValidationException.class, () -> f.backend.prepareTransaction(PactState.empty(),
                    new PactState(Set.of(cluster("elastic", "monitor")))));
        }
    }

    @Test
    void compensatesAnAmbiguousSuccessfulUserWriteAfterTransportFailure() throws Exception {
        try (var f = new Fixture(ElasticsearchReconciliationMode.AUTHORITATIVE)) {
            String user = f.createUser("rollback"), external = f.role("external");
            f.client.putUser(user, JSON.readTree("{\"roles\":[\"" + external + "\"]}"));
            var fault = new ObservedClient(f.client);
            var backend = new ElasticsearchBackend(BACKEND, f.config, fault);
            var tx = backend.prepareTransaction(PactState.empty(), new PactState(Set.of(cluster(user, "monitor"))));
            assertThrows(BackendOperationException.class, tx::apply);
            assertEquals(Set.of(roleName(user)), roles(f.client.getUser(user)), "Write reached the real server before fault");
            tx.rollback();
            assertEquals(Set.of(), roles(f.client.getUser(user)));
            assertNull(f.client.getRole(roleName(user)));
        }
    }

    @Test
    void officialClientRequiresTheConfiguredCaForPrivateTlsServer() throws Exception {
        String ca = System.getenv("PACT_IT_ELASTICSEARCH_CA_CERTIFICATE");
        org.junit.jupiter.api.Assumptions.assumeTrue(ca != null && !ca.isBlank(),
                "Private CA test requires PACT_IT_ELASTICSEARCH_CA_CERTIFICATE; use the focused TLS stack");
        var config = config(ElasticsearchReconciliationMode.MANAGED, null);
        try (var untrusted = new ElasticsearchHttpClient(config)) {
            assertThrows(ElasticsearchClientException.class, untrusted::getPrivileges);
        }
        try (var trusted = new ElasticsearchHttpClient(config(ElasticsearchReconciliationMode.MANAGED, Path.of(ca)))) {
            assertTrue(trusted.getPrivileges().cluster().contains("monitor"));
        }
    }

    private static Identity identity(String user, String version, String password) {
        return new Identity(BACKEND, user, true, "integration/password", version, new SecretValue(password));
    }
    private static Access indices(String user, String index, String privilege) {
        return new Access(user, new Resource(BACKEND, Map.of("index", index)),
                Map.of("permissions", Value.object(Map.of("indices", Value.set(Set.of(Value.string(privilege)))))));
    }
    private static Access cluster(String user, String privilege, String... roles) {
        var attributes = new HashMap<String, Value>();
        if (privilege != null) attributes.put("permissions", Value.object(Map.of("cluster", Value.set(Set.of(Value.string(privilege))))));
        if (roles.length > 0) attributes.put("roles", Value.set(Arrays.stream(roles).map(Value::string).collect(java.util.stream.Collectors.toSet())));
        return new Access(user, new Resource(BACKEND, Map.of("cluster", "*")), attributes);
    }
    private static String roleName(String user) { return ElasticsearchRoleCompiler.roleName(BACKEND, user); }
    private static Set<String> roles(JsonNode user) {
        var result = new TreeSet<String>();
        user.path("roles").forEach(role -> result.add(role.asText()));
        return result;
    }
    private static ElasticsearchConfig config(ElasticsearchReconciliationMode mode, Path ca) {
        return new ElasticsearchConfig(URI.create(required("PACT_IT_ELASTICSEARCH_ENDPOINT")),
                required("PACT_IT_ELASTICSEARCH_USERNAME"), required("PACT_IT_ELASTICSEARCH_PASSWORD"), null, "ensure", mode, ca);
    }
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing integration environment variable: " + name);
        return value;
    }

    private static final class Fixture implements AutoCloseable {
        final String prefix = "pact-it-" + UUID.randomUUID().toString().replace("-", "") + "-";
        final ElasticsearchConfig config;
        final ElasticsearchHttpClient client;
        final ElasticsearchBackend backend;
        final HttpClient http;
        final Set<String> users = new TreeSet<>(), sharedRoles = new TreeSet<>(), indices = new TreeSet<>();

        Fixture(ElasticsearchReconciliationMode mode) throws Exception {
            String ca = System.getenv("PACT_IT_ELASTICSEARCH_CA_CERTIFICATE");
            config = config(mode, ca == null || ca.isBlank() ? null : Path.of(ca));
            client = new ElasticsearchHttpClient(config);
            backend = new ElasticsearchBackend(BACKEND, config, client);
            var builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10));
            if (config.caCertificate() != null) builder.sslContext(trust(config.caCertificate()));
            http = builder.build();
        }
        String user(String suffix) throws Exception {
            String name = prefix + suffix;
            assertNull(client.getUser(name), "Unique integration user must be absent");
            users.add(name);
            return name;
        }
        String createUser(String suffix) throws Exception {
            String user = user(suffix);
            client.putUser(user, JSON.readTree("{\"password\":\"Fixture-it-Dd4!\",\"roles\":[]}"));
            return user;
        }
        String index(String suffix) { String name = prefix + suffix; indices.add(name); return name; }
        String role(String suffix) throws Exception {
            String name = prefix + suffix;
            assertNull(client.getRole(name));
            sharedRoles.add(name);
            client.putRole(name, JSON.readTree("{\"cluster\":[\"monitor\"],\"indices\":[{\"names\":[\"" + prefix
                    + "*\"],\"privileges\":[\"read\"]}],\"metadata\":{\"owner\":\"integration-operator\"}}"));
            return name;
        }
        void admin(String method, String path, String body, int status) throws Exception {
            as(config.username(), config.password(), method, path, body, status);
        }
        void as(String user, String password, String method, String path, String body, int status) throws Exception {
            var request = HttpRequest.newBuilder(config.endpoint().resolve(path)).timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8)))
                    .header("Content-Type", "application/json")
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build();
            var response = http.send(request, HttpResponse.BodyHandlers.discarding());
            assertEquals(status, response.statusCode(), method + " " + path);
        }
        @Override public void close() throws Exception {
            Exception failure = null;
            for (String user : users) {
                try {
                    var role = client.getRole(roleName(user));
                    if (role != null && user.equals(role.path("metadata").path("pact_principal").asText())
                            && BACKEND.equals(role.path("metadata").path("pact_backend_id").asText())) client.deleteRole(roleName(user));
                    if (client.getUser(user) != null) admin("DELETE", "/_security/user/" + user, null, 200);
                } catch (Exception e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
            }
            for (String role : sharedRoles) { try { client.deleteRole(role); } catch (Exception e) { if (failure == null) failure = e; else failure.addSuppressed(e); } }
            for (String index : indices) { try { admin("DELETE", "/" + index, null, 200); } catch (Exception e) { if (failure == null) failure = e; else failure.addSuppressed(e); } }
            try { backend.close(); } finally { http.close(); }
            if (failure != null) throw failure;
        }
    }

    private static SSLContext trust(Path ca) throws Exception {
        var store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        try (var input = Files.newInputStream(ca)) {
            int i = 0;
            for (var cert : CertificateFactory.getInstance("X.509").generateCertificates(input)) store.setCertificateEntry("ca-" + i++, cert);
        }
        var managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        managers.init(store);
        var ssl = SSLContext.getInstance("TLS");
        ssl.init(null, managers.getTrustManagers(), null);
        return ssl;
    }

    private static final class ObservedClient implements ElasticsearchClient {
        private final ElasticsearchClient delegate;
        private boolean failAfterUserWrite = true;
        private int writes;
        ObservedClient(ElasticsearchClient delegate) { this.delegate = delegate; }
        public ElasticsearchPrivileges getPrivileges() throws ElasticsearchClientException { return delegate.getPrivileges(); }
        public JsonNode getRole(String name) throws ElasticsearchClientException { return delegate.getRole(name); }
        public JsonNode getUser(String name) throws ElasticsearchClientException { return delegate.getUser(name); }
        public void putRole(String name, JsonNode body) throws ElasticsearchClientException { writes++; delegate.putRole(name, body); }
        public void deleteRole(String name) throws ElasticsearchClientException { writes++; delegate.deleteRole(name); }
        public void updatePassword(String name, String password) throws ElasticsearchClientException { writes++; delegate.updatePassword(name, password); }
        public void putUser(String name, JsonNode body) throws ElasticsearchClientException {
            writes++;
            delegate.putUser(name, body);
            if (failAfterUserWrite) { failAfterUserWrite = false; throw new ElasticsearchClientException("Injected lost response after successful user update"); }
        }
    }
}
