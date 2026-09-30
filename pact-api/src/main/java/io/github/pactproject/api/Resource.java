package io.github.pactproject.api;

import java.util.Map;

public record Resource(
        String backendId,
        Map<String, String> target
) {
    public Resource {
        target = Map.copyOf(target);
    }
}
