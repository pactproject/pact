package io.github.pactproject.elasticsearch.client;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import io.github.pactproject.elasticsearch.ElasticsearchConfig;
import io.github.pactproject.elasticsearch.ElasticsearchReconciliationMode;
import io.github.pactproject.elasticsearch.api.ElasticsearchClientException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ElasticsearchTlsTest {
    @TempDir static Path directory;
    static HttpsServer server;
    static Path ca, wrongCa;

    @BeforeAll
    static void start() throws Exception {
        ca = certificate("server");
        wrongCa = certificate("unrelated");
        var store = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(directory.resolve("server.p12"))) {
            store.load(input, "test-password".toCharArray());
        }
        var keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, "test-password".toCharArray());
        var context = SSLContext.getInstance("TLS");
        context.init(keys.getKeyManagers(), null, null);
        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(context));
        server.createContext("/_security/privilege/_builtin", exchange -> {
            byte[] body = "{\"cluster\":[\"monitor\"],\"index\":[\"read\"]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("X-elastic-product", "Elasticsearch");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterAll static void stop() { if (server != null) server.stop(0); }

    @Test void trustsOnlyConfiguredBundleAndStillVerifiesHostname() throws Exception {
        try (var trusted = new ElasticsearchHttpClient(config("localhost", ca))) {
            assertEquals(Set.of("monitor"), trusted.getPrivileges().cluster());
        }
        for (var config : List.of(config("localhost", null), config("localhost", wrongCa), config("127.0.0.1", ca))) {
            try (var rejected = new ElasticsearchHttpClient(config)) {
                assertThrows(ElasticsearchClientException.class, rejected::getPrivileges);
            }
        }
    }

    @Test void rejectsMissingOrInvalidBundleDuringInitialization() throws Exception {
        var invalid = directory.resolve("invalid.pem");
        Files.writeString(invalid, "not a certificate");
        assertThrows(IllegalArgumentException.class, () -> new ElasticsearchHttpClient(config("localhost", invalid)));
        assertThrows(IllegalArgumentException.class, () -> new ElasticsearchHttpClient(config("localhost", directory.resolve("missing.pem"))));
    }

    private static ElasticsearchConfig config(String host, Path certificate) {
        return new ElasticsearchConfig(URI.create("https://" + host + ":" + server.getAddress().getPort()),
                "pact", "test-credential", null, "require", ElasticsearchReconciliationMode.MANAGED, certificate);
    }
    private static Path certificate(String name) throws Exception {
        var store = directory.resolve(name + ".p12");
        var cert = directory.resolve(name + ".pem");
        keytool("-genkeypair", "-alias", name, "-keyalg", "RSA", "-keysize", "2048", "-validity", "1",
                "-storetype", "PKCS12", "-keystore", store.toString(), "-storepass", "test-password",
                "-dname", "CN=localhost", "-ext", "SAN=dns:localhost");
        keytool("-exportcert", "-rfc", "-alias", name, "-keystore", store.toString(), "-storepass", "test-password", "-file", cert.toString());
        return cert;
    }
    private static void keytool(String... args) throws Exception {
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
        command.addAll(List.of(args));
        var log = directory.resolve("keytool.log");
        var process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Certificate generation timed out");
        assertEquals(0, process.exitValue(), () -> "Certificate generation failed; see " + log);
    }
}
