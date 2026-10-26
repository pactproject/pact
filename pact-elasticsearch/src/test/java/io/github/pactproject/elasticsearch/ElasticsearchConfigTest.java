package io.github.pactproject.elasticsearch;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElasticsearchConfigTest {
    @org.junit.jupiter.api.Test
    void discoversRegisteredBackendFactory() {
        var factory = java.util.ServiceLoader.load(io.github.pactproject.api.BackendFactory.class)
                .stream().filter(provider -> provider.type().equals(ElasticsearchBackendFactory.class))
                .findFirst().orElseThrow().get();
        org.junit.jupiter.api.Assertions.assertEquals("elasticsearch", factory.type());
    }

    @Test
    void readsBasicAuthenticationAndDefaultsToRequire() {
        ElasticsearchConfig config = ElasticsearchConfig.from(Map.of(
                "endpoint", "https://search.example:9200",
                "username", "pact",
                "password", "secret"
        ));

        assertEquals(URI.create("https://search.example:9200"),
                config.endpoint());
        assertEquals("require", config.principalMode());
        assertEquals(ElasticsearchReconciliationMode.AUTHORITATIVE, config.reconciliationMode());
        assertEquals(ElasticsearchReconciliationMode.AUTHORITATIVE,
                new ElasticsearchConfig(config.endpoint(), "pact", "secret", null, "require").reconciliationMode());
        assertTrue(!config.toString().contains("secret"));
    }

    @Test
    void readsAuthoritativeModeAndCaAndRejectsTyposBeforeConnecting() {
        var values = new java.util.HashMap<>(Map.of("endpoint", "https://localhost:9200", "api-key", "key",
                "reconciliation-mode", "authoritative", "ca-certificate", "/certificates/ca.crt"));
        var config = ElasticsearchConfig.from(values);
        assertEquals(ElasticsearchReconciliationMode.AUTHORITATIVE, config.reconciliationMode());
        assertEquals(java.nio.file.Path.of("/certificates/ca.crt"), config.caCertificate());
        values.put("reconciliation-mode", "authoratative");
        assertThrows(IllegalArgumentException.class, () -> ElasticsearchConfig.from(values));
        values.put("reconciliation-mode", "managed");
        values.put("endpoint", "http://localhost:9200");
        assertThrows(IllegalArgumentException.class, () -> ElasticsearchConfig.from(values));
        values.put("endpoint", "https://localhost:9200");
        values.put("ca-certficate", "/typo");
        assertThrows(IllegalArgumentException.class, () -> ElasticsearchConfig.from(values));
    }

    @Test
    void readsApiKeyAndEnsureMode() {
        ElasticsearchConfig config = ElasticsearchConfig.from(Map.of(
                "endpoint", "http://localhost:9200",
                "api-key", "encoded-key",
                "principal-mode", "ensure"
        ));

        assertEquals("encoded-key", config.apiKey());
        assertEquals("ensure", config.principalMode());
    }

    @Test
    void rejectsMissingOrAmbiguousAuthenticationAndInvalidEndpoint() {
        assertThrows(IllegalArgumentException.class, () -> ElasticsearchConfig.from(Map.of("endpoint", "http://localhost:9200",
                "username", "pact", "password", "secret", "api-key", "")));
        var invalidId = assertThrows(IllegalArgumentException.class, () -> new ElasticsearchBackendFactory().create("", Map.of()));
        assertTrue(invalidId.getMessage().contains("backend id"));
        assertThrows(IllegalArgumentException.class, () ->
                ElasticsearchConfig.from(Map.of("endpoint",
                        "http://localhost:9200")));
        assertThrows(IllegalArgumentException.class, () ->
                ElasticsearchConfig.from(Map.of(
                        "endpoint", "http://localhost:9200",
                        "username", "pact"
                )));
        assertThrows(IllegalArgumentException.class, () ->
                ElasticsearchConfig.from(Map.of(
                        "endpoint", "http://localhost:9200",
                        "username", "pact",
                        "password", "secret",
                        "api-key", "key"
                )));
        assertThrows(IllegalArgumentException.class, () ->
                ElasticsearchConfig.from(Map.of(
                        "endpoint", "file:///tmp/es",
                        "api-key", "key"
                )));
    }
}
