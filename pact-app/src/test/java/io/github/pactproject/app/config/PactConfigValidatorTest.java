package io.github.pactproject.app.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PactConfigValidatorTest
{
    private final PactConfigValidator validator =
            new PactConfigValidator();

    @Test
    void acceptsValidConfiguration()
    {
        PactConfig config = new PactConfig(
                new StateProviderConfig(
                        "filesystem",
                        Map.of("path", "./pact-state.yaml")
                ),
                List.of(
                        new BackendConfig(
                                "artifact-keeper-prod",
                                "artifact-keeper",
                                Map.of()
                        )
                )
        );

        assertDoesNotThrow(
                () -> validator.validate(config)
        );
    }

    @Test
    void rejectsNullConfiguration()
    {
        assertThrows(
                PactConfigException.class,
                () -> validator.validate(null)
        );
    }

    @Test
    void rejectsMissingStateProvider()
    {
        PactConfig config = new PactConfig(
                null,
                List.of()
        );

        assertThrows(
                PactConfigException.class,
                () -> validator.validate(config)
        );
    }

    @Test
    void rejectsEmptyStateProviderType()
    {
        PactConfig config = new PactConfig(
                new StateProviderConfig(
                        " ",
                        Map.of()
                ),
                List.of()
        );

        assertThrows(
                PactConfigException.class,
                () -> validator.validate(config)
        );
    }

    @Test
    void rejectsEmptyBackendId()
    {
        PactConfig config = new PactConfig(
                new StateProviderConfig(
                        "filesystem",
                        Map.of()
                ),
                List.of(
                        new BackendConfig(
                                "",
                                "artifact-keeper",
                                Map.of()
                        )
                )
        );

        assertThrows(
                PactConfigException.class,
                () -> validator.validate(config)
        );
    }

    @Test
    void rejectsEmptyBackendType()
    {
        PactConfig config = new PactConfig(
                new StateProviderConfig(
                        "filesystem",
                        Map.of()
                ),
                List.of(
                        new BackendConfig(
                                "backend",
                                "",
                                Map.of()
                        )
                )
        );

        assertThrows(
                PactConfigException.class,
                () -> validator.validate(config)
        );
    }

    @Test
    void rejectsDuplicateBackendIds()
    {
        PactConfig config = new PactConfig(
                new StateProviderConfig(
                        "filesystem",
                        Map.of()
                ),
                List.of(
                        new BackendConfig(
                                "artifact-keeper",
                                "artifact-keeper",
                                Map.of()
                        ),
                        new BackendConfig(
                                "artifact-keeper",
                                "artifact-keeper",
                                Map.of()
                        )
                )
        );

        assertThrows(
                PactConfigException.class,
                () -> validator.validate(config)
        );
    }
}
