package io.github.pactproject.api;

import io.github.pactproject.api.value.Value;

import java.util.Map;

public record Access(
        String principal,
        Resource resource,
        Map<String, Value> attributes
) {
    public Access {
        attributes = Map.copyOf(attributes);
    }
}
