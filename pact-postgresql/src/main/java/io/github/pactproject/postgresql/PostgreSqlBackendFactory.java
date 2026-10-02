package io.github.pactproject.postgresql;

import io.github.pactproject.api.Backend;
import io.github.pactproject.api.BackendFactory;
import io.github.pactproject.postgresql.client.JdbcPostgreSqlClient;

import java.util.Map;

public final class PostgreSqlBackendFactory implements BackendFactory {
    @Override
    public String type() {
        return "postgresql";
    }

    @Override
    public Backend create(String id, Map<String, String> config) {
        PostgreSqlConfig postgreSqlConfig = PostgreSqlConfig.from(config);
        return new PostgreSqlBackend(
                id,
                new JdbcPostgreSqlClient(postgreSqlConfig)
        );
    }
}
