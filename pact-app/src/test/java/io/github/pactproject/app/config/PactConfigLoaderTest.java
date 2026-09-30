package io.github.pactproject.app.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PactConfigLoaderTest
{
    @TempDir
    Path tempDir;

    @Test
    void loadsConfiguration()
            throws Exception
    {
        Path file = tempDir.resolve("pact.yaml");

        Files.writeString(
                file,
                """
                stateProvider:
                  type: filesystem
                  config:
                    path: ./pact-state.yaml

                backends:
                  - id: artifact-keeper-prod
                    type: artifact-keeper
                    config:
                      url: http://artifact-keeper:8080
                      token: secret
                      per-page: "100"

                  - id: artifact-keeper-test
                    type: artifact-keeper
                    config:
                      url: http://artifact-keeper-test:8080
                      token: test-secret
                """
        );

        PactConfig config =
                new PactConfigLoader().load(file);

        assertEquals(
                "filesystem",
                config.stateProvider().type()
        );

        assertEquals(
                "./pact-state.yaml",
                config.stateProvider()
                        .config()
                        .get("path")
        );

        assertEquals(
                2,
                config.backends().size()
        );

        BackendConfig production =
                config.backends().get(0);

        assertEquals(
                "artifact-keeper-prod",
                production.id()
        );

        assertEquals(
                "artifact-keeper",
                production.type()
        );

        assertEquals(
                "http://artifact-keeper:8080",
                production.config().get("url")
        );

        assertEquals(
                "secret",
                production.config().get("token")
        );

        assertEquals(
                "100",
                production.config().get("per-page")
        );

        BackendConfig test =
                config.backends().get(1);

        assertEquals(
                "artifact-keeper-test",
                test.id()
        );

        assertEquals(
                "http://artifact-keeper-test:8080",
                test.config().get("url")
        );

        assertEquals(
                "test-secret",
                test.config().get("token")
        );
    }

    @Test
    void rejectsInvalidYaml()
            throws Exception
    {
        Path file = tempDir.resolve("pact.yaml");

        Files.writeString(
                file,
                """
                stateProvider:
                  type: [broken
                """
        );

        assertThrows(
                PactConfigException.class,
                () -> new PactConfigLoader().load(file)
        );
    }

    @Test
    void rejectsMissingFile()
    {
        Path file = tempDir.resolve("missing.yaml");

        assertThrows(
                PactConfigException.class,
                () -> new PactConfigLoader().load(file)
        );
    }
}
