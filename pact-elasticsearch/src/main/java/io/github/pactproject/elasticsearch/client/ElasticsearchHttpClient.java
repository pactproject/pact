package io.github.pactproject.elasticsearch.client;

import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.json.JsonpSerializable;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest5_client.Rest5ClientTransport;
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pactproject.elasticsearch.ElasticsearchConfig;
import io.github.pactproject.elasticsearch.api.ElasticsearchClient;
import io.github.pactproject.elasticsearch.api.ElasticsearchClientException;
import io.github.pactproject.elasticsearch.api.ElasticsearchPrivileges;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.message.BasicHeader;
import org.apache.hc.core5.util.Timeout;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.util.Base64;
import java.util.Set;

/** Boundary adapter for the official 9.2 Java API client; SDK objects stay here. */
public final class ElasticsearchHttpClient implements ElasticsearchClient {
    private final ObjectMapper json = new ObjectMapper();
    private final JacksonJsonpMapper mapper = new JacksonJsonpMapper(json);
    private final co.elastic.clients.elasticsearch.ElasticsearchClient client;
    private final String[] credentials;

    public ElasticsearchHttpClient(ElasticsearchConfig config) {
        SSLContext ssl = config.caCertificate() == null ? null : caContext(config.caCertificate());
        var endpoint = config.endpoint();
        var host = java.net.URI.create(endpoint.getScheme() + "://" + endpoint.getRawAuthority());
        String authorization = config.apiKey() != null ? "ApiKey " + config.apiKey()
                : "Basic " + Base64.getEncoder().encodeToString((config.username() + ":" + config.password()).getBytes(StandardCharsets.UTF_8));
        credentials = new String[] {config.password(), config.apiKey(), authorization,
                authorization.substring(authorization.indexOf(' ') + 1)};
        var builder = Rest5Client.builder(host).setDefaultHeaders(new Header[]{new BasicHeader("Authorization", authorization)})
                .setRequestConfigCallback(b -> b.setResponseTimeout(Timeout.ofSeconds(30))
                        .setConnectionRequestTimeout(Timeout.ofSeconds(30)))
                .setConnectionConfigCallback(b -> b.setConnectTimeout(Timeout.ofSeconds(10)));
        if (endpoint.getRawPath() != null && !endpoint.getRawPath().isBlank() && !endpoint.getRawPath().equals("/")) {
            builder.setPathPrefix(endpoint.getRawPath());
        }
        if (ssl != null) builder.setSSLContext(ssl);
        client = new co.elastic.clients.elasticsearch.ElasticsearchClient(new Rest5ClientTransport(builder.build(), mapper));
    }

    @Override public ElasticsearchPrivileges getPrivileges() throws ElasticsearchClientException {
        return call("read supported privileges", () -> {
            var response = client.security().getBuiltinPrivileges();
            if (response.cluster().isEmpty() || response.index().isEmpty()) {
                throw new ElasticsearchClientException("Elasticsearch returned an incomplete built-in privilege catalog");
            }
            return new ElasticsearchPrivileges(Set.copyOf(response.cluster()), Set.copyOf(response.index()));
        });
    }

    @Override public JsonNode getRole(String name) throws ElasticsearchClientException {
        return missingAllowed("read role", () -> node(client.security().getRole(r -> r.name(name)).roles().get(name)));
    }

    @Override public JsonNode getUser(String username) throws ElasticsearchClientException {
        return missingAllowed("read user", () -> node(client.security().getUser(r -> r.username(username)).users().get(username)));
    }

    @Override public void putRole(String name, JsonNode definition) throws ElasticsearchClientException {
        var body = definition.deepCopy();
        if (body instanceof com.fasterxml.jackson.databind.node.ObjectNode object) object.remove("transient_metadata");
        call("write role", () -> client.security().putRole(r -> r.name(name).withJson(new StringReader(body.toString()))));
    }

    @Override public void deleteRole(String name) throws ElasticsearchClientException {
        call("delete role", () -> client.security().deleteRole(r -> r.name(name)));
    }

    @Override public void putUser(String username, JsonNode definition) throws ElasticsearchClientException {
        call("write user", () -> client.security().putUser(r -> r.username(username).withJson(new StringReader(definition.toString()))),
                definition.path("password").asText(null));
    }

    @Override public void updatePassword(String username, String password) throws ElasticsearchClientException {
        call("change user password", () -> client.security().changePassword(r -> r.username(username).password(password)), password);
    }

    @Override public void close() throws ElasticsearchClientException {
        call("close client", () -> { client.close(); return null; });
    }

    private JsonNode node(JsonpSerializable value) throws java.io.IOException {
        if (value == null) return null;
        var out = new StringWriter();
        try (var generator = mapper.jsonProvider().createGenerator(out)) { value.serialize(generator, mapper); }
        return json.readTree(out.toString());
    }

    private <T> T missingAllowed(String operation, Request<T> request) throws ElasticsearchClientException {
        try { return request.run(); }
        catch (ElasticsearchException e) {
            if (e.status() == 404) return null;
            throw failure(operation, e.status(), e);
        } catch (Exception e) { throw failure(operation, null, e); }
    }

    private <T> T call(String operation, Request<T> request, String... secrets) throws ElasticsearchClientException {
        try { return request.run(); }
        catch (ElasticsearchException e) { throw failure(operation, e.status(), e, secrets); }
        catch (Exception e) { throw failure(operation, null, e, secrets); }
    }

    private ElasticsearchClientException failure(String operation, Integer status, Exception error, String... secrets) {
        var sensitive = new java.util.ArrayList<String>(java.util.Arrays.asList(credentials));
        sensitive.addAll(java.util.Arrays.asList(secrets));
        // Preserve sanitized details, but never attach an SDK exception retaining the raw response.
        return new ElasticsearchClientException("Elasticsearch failed to " + operation
                + (status == null ? " (transport or response failure)" : " (HTTP " + status + ")")
                + ": " + error.getClass().getSimpleName() + ": "
                + io.github.pactproject.api.DiagnosticSanitizer.redact(error.getMessage(), sensitive.toArray(String[]::new)));
    }

    private static SSLContext caContext(Path path) {
        try (var input = Files.newInputStream(path)) {
            var certificates = CertificateFactory.getInstance("X.509").generateCertificates(input);
            if (certificates.isEmpty()) throw new IllegalArgumentException("Empty CA certificate bundle");
            var store = KeyStore.getInstance("PKCS12");
            store.load(null, null);
            int index = 0;
            for (var certificate : certificates) store.setCertificateEntry("ca-" + index++, certificate);
            var managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            managers.init(store);
            var context = SSLContext.getInstance("TLS");
            context.init(null, managers.getTrustManagers(), null);
            return context;
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot load Elasticsearch CA certificate bundle: " + path, e);
        }
    }

    @FunctionalInterface private interface Request<T> { T run() throws Exception; }
}
