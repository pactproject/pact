package io.github.pactproject.api;

import java.util.Map;

public interface BackendFactory
{
    String type();

    Backend create(String id, Map<String, String> config);
}
