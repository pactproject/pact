package io.github.pactproject.elasticsearch;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElasticsearchConfigTest {
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
        assertTrue(!config.toString().contains("secret"));
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
