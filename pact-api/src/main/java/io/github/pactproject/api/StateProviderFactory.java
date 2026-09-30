package io.github.pactproject.api;

import java.util.Map;

public interface StateProviderFactory {
    String type();

    StateProvider create(Map<String, String> config);
}
