package io.github.pactproject.elasticsearch.model;

import java.util.List;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

public record ElasticsearchRole(
        String principal,
        String name,
        Map<String, List<String>> indices
) {
    public ElasticsearchRole {
        Map<String, List<String>> sorted = new TreeMap<>();
        indices.forEach((index, privileges) ->
                sorted.put(index, List.copyOf(privileges)));
        indices = Collections.unmodifiableMap(sorted);
    }
}
