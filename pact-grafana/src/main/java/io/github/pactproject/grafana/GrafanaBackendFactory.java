package io.github.pactproject.grafana;

import io.github.pactproject.api.*;
import io.github.pactproject.grafana.client.GrafanaHttpClient;
import java.util.Map;

public final class GrafanaBackendFactory implements BackendFactory {
    @Override public String type() { return "grafana"; }
    @Override public Backend create(String id, Map<String, String> config) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Grafana backend id must not be blank");
        var parsed = GrafanaConfig.from(config);
        var client = new GrafanaHttpClient(parsed);
        try { return new GrafanaBackend(id, client); }
        catch (RuntimeException e) { client.close(); throw e; }
    }
}
