package io.github.pactproject.elasticsearch;

import io.github.pactproject.api.Backend;
import io.github.pactproject.api.BackendFactory;
import io.github.pactproject.elasticsearch.client.ElasticsearchHttpClient;

import java.util.Map;

public final class ElasticsearchBackendFactory implements BackendFactory {
    @Override
    public String type() {
        return "elasticsearch";
    }

    @Override
    public Backend create(String id, Map<String, String> config) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Elasticsearch backend id must not be blank");
        ElasticsearchConfig parsed = ElasticsearchConfig.from(config);
        return new ElasticsearchBackend(
                id,
                parsed,
                new ElasticsearchHttpClient(parsed)
        );
    }
}
