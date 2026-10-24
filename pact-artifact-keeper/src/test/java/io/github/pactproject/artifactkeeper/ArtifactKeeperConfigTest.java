package io.github.pactproject.artifactkeeper;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ArtifactKeeperConfigTest {
    @Test
    void defaultsToCurrentForOmittedProfileAndConvenienceConstructor() {
        assertEquals(ArtifactKeeperApiProfile.CURRENT,
                ArtifactKeeperConfig.from(Map.of("url", "http://localhost", "token", "test")).apiProfile());
        assertEquals(ArtifactKeeperApiProfile.CURRENT,
                new ArtifactKeeperConfig(java.net.URI.create("http://localhost"), "test", 100).apiProfile());
    }

    @Test
    void acceptsCurrentAndRejectsUnknownProfiles() {
        var config = Map.of("url", "http://localhost", "token", "test", "api-profile", "current");
        assertEquals(ArtifactKeeperApiProfile.CURRENT, ArtifactKeeperConfig.from(config).apiProfile());
        assertThrows(IllegalArgumentException.class, () -> ArtifactKeeperConfig.from(
                Map.of("url", "http://localhost", "token", "test", "api-profile", "auto")
        ));
    }

    @Test
    void rejectsRetiredLegacyProfileWithMigrationInstructions() {
        var error = assertThrows(IllegalArgumentException.class, () -> ArtifactKeeperConfig.from(
                Map.of("url", "http://localhost", "token", "test", "api-profile", "legacy")));
        assertTrue(error.getMessage().contains("1.5.1"));
        assertTrue(error.getMessage().contains("no longer supported"));
        assertTrue(error.getMessage().contains("1.11.1"));
        assertTrue(error.getMessage().contains("current"));
    }

    @Test
    void rejectsLegacyDuringBackendInitializationBeforeAnyApiRequest() throws Exception {
        var requests = new java.util.concurrent.atomic.AtomicInteger();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        try {
            var config = Map.of("url", "http://127.0.0.1:" + server.getAddress().getPort(),
                    "token", "test", "api-profile", "legacy");
            var error = assertThrows(IllegalArgumentException.class,
                    () -> new ArtifactKeeperBackendFactory().create("packages", config));
            assertTrue(error.getMessage().contains("api-profile=legacy"));
            assertEquals(0, requests.get(), "Retired configuration must fail before contacting Artifact Keeper");
        } finally { server.stop(0); }
    }
}
