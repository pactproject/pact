package io.github.pactproject.app.config;

import java.util.HashSet;
import java.util.Set;

public final class PactConfigValidator
{
    public void validate(PactConfig config)
            throws PactConfigException
    {
        if (config == null) {
            throw new PactConfigException(
                    "PACT configuration must not be null"
            );
        }

        validateStateProvider(config);
        validateBackends(config);
    }

    private void validateStateProvider(
            PactConfig config)
            throws PactConfigException
    {
        if (config.stateProvider() == null) {
            throw new PactConfigException(
                    "State provider must be configured"
            );
        }

        if (isBlank(config.stateProvider().type())) {
            throw new PactConfigException(
                    "State provider type must not be empty"
            );
        }
    }

    private void validateBackends(
            PactConfig config)
            throws PactConfigException
    {
        if (config.backends() == null) {
            throw new PactConfigException(
                    "Backends must not be null"
            );
        }

        Set<String> ids = new HashSet<>();

        for (BackendConfig backend : config.backends()) {
            if (backend == null) {
                throw new PactConfigException(
                        "Backend must not be null"
                );
            }

            if (isBlank(backend.id())) {
                throw new PactConfigException(
                        "Backend id must not be empty"
                );
            }

            if (isBlank(backend.type())) {
                throw new PactConfigException(
                        "Backend type must not be empty"
                );
            }

            if (!ids.add(backend.id())) {
                throw new PactConfigException(
                        "Duplicate backend id: "
                                + backend.id()
                );
            }
        }
    }

    private boolean isBlank(String value)
    {
        return value == null || value.isBlank();
    }
}
