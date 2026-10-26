package io.github.pactproject.elasticsearch.api;

import java.util.Set;

/** Names supported by the connected server, read before permission writes. */
public record ElasticsearchPrivileges(Set<String> cluster, Set<String> indices) {
    public ElasticsearchPrivileges {
        cluster = Set.copyOf(cluster);
        indices = Set.copyOf(indices);
    }
}
