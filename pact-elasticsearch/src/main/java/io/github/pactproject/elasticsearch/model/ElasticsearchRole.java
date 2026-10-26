package io.github.pactproject.elasticsearch.model;

import java.util.List;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

public record ElasticsearchRole(
        String principal,
        String name,
        Map<String, List<String>> indices,
        java.util.Set<String> cluster,
        java.util.Set<String> assignedRoles
) {
    public ElasticsearchRole {
        Map<String, List<String>> sorted = new TreeMap<>();
        indices.forEach((index, privileges) ->
                sorted.put(index, List.copyOf(privileges)));
        indices = Collections.unmodifiableMap(sorted);
        cluster = Collections.unmodifiableSet(new java.util.TreeSet<>(cluster));
        assignedRoles = Collections.unmodifiableSet(new java.util.TreeSet<>(assignedRoles));
    }

    public ElasticsearchRole(String principal, String name, Map<String, List<String>> indices) {
        this(principal, name, indices, java.util.Set.of(), java.util.Set.of());
    }

    public boolean hasPrivileges() { return !indices.isEmpty() || !cluster.isEmpty(); }
}
