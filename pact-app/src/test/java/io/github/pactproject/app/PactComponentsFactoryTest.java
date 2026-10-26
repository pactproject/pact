package io.github.pactproject.app;

import io.github.pactproject.api.*;
import io.github.pactproject.app.config.*;
import io.github.pactproject.app.plugin.*;
import org.junit.jupiter.api.Test;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PactComponentsFactoryTest {
    @Test
    void closesAlreadyCreatedBackendsWhenLaterInitializationFails() throws Exception {
        var closed = new ArrayList<String>();
        var backendFactory = new BackendFactory() {
            public String type() { return "test"; }
            public Backend create(String id, Map<String, String> config) {
                if (id.equals("bad")) throw new IllegalArgumentException("Invalid backend configuration");
                return new Backend() {
                    public String id() { return id; }
                    public void apply(PactState state) { }
                    public void close() { closed.add(id); }
                };
            }
        };
        var providerFactory = new StateProviderFactory() {
            public String type() { return "test"; }
            public StateProvider create(Map<String, String> config) { return PactState::empty; }
        };
        try (var plugins = new PluginSet(new URLClassLoader(new URL[0]), List.of(backendFactory), List.of(providerFactory))) {
            var factory = new PactComponentsFactory(new PluginRegistry(plugins));
            var config = new PactConfig(new StateProviderConfig("test", Map.of()), List.of(
                    new BackendConfig("first", "test", Map.of()), new BackendConfig("second", "test", Map.of()),
                    new BackendConfig("bad", "test", Map.of())));
            assertThrows(PactComponentsException.class, () -> factory.create(config));
            assertEquals(List.of("second", "first"), closed);
        }
    }
}
