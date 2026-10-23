package io.github.pactproject.artifactkeeper;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ArtifactKeeperConfigTest {
    @Test
    void defaultsToLegacyForExistingConfigurations() {
        assertEquals(ArtifactKeeperApiProfile.LEGACY,
                ArtifactKeeperConfig.from(Map.of("url", "http://localhost", "token", "test")).apiProfile());
    }

    @Test
    void acceptsCurrentAndRejectsUnknownProfiles() {
        var config = Map.of("url", "http://localhost", "token", "test", "api-profile", "current");
        assertEquals(ArtifactKeeperApiProfile.CURRENT, ArtifactKeeperConfig.from(config).apiProfile());
        assertThrows(IllegalArgumentException.class, () -> ArtifactKeeperConfig.from(
                Map.of("url", "http://localhost", "token", "test", "api-profile", "auto")
        ));
    }
}
