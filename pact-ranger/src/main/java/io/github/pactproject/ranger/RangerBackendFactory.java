package io.github.pactproject.ranger;

import io.github.pactproject.api.Backend;
import io.github.pactproject.api.BackendFactory;
import io.github.pactproject.ranger.client.RangerHttpClient;

import java.util.Map;

public final class RangerBackendFactory implements BackendFactory {
    @Override
    public String type() {
        return "ranger";
    }

    @Override
    public Backend create(String id, Map<String, String> config) {
        RangerConfig rangerConfig = RangerConfig.from(config);
        return new RangerBackend(
                id,
                rangerConfig,
                new RangerHttpClient(rangerConfig)
        );
    }
}
