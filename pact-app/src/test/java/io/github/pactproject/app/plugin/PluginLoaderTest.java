package io.github.pactproject.app.plugin;

import io.github.pactproject.api.BackendFactory;
import io.github.pactproject.api.PactState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class PluginLoaderTest {
    @TempDir Path directory;

    @Test
    void discoversPackagedBackendWithSharedApiAndIndependentInstanceIds() throws Exception {
        Path classes = Files.createDirectory(directory.resolve("classes"));
        Path source = directory.resolve("ExampleFactory.java");
        Files.writeString(source, """
                package fixture;
                import io.github.pactproject.api.*;
                import java.util.Map;
                public class ExampleFactory implements BackendFactory {
                    public String type() { return "example"; }
                    public Backend create(String id, Map<String, String> config) {
                        return new Backend() {
                            public String id() { return id; }
                            public void apply(PactState state) { }
                        };
                    }
                }
                """);
        String api = Path.of(BackendFactory.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "--enable-preview", "--release", "21", "-classpath", api,
                "-d", classes.toString(), source.toString()));
        Path plugins = Files.createDirectory(directory.resolve("plugins"));
        try (var jar = new JarOutputStream(Files.newOutputStream(plugins.resolve("example.jar")))) {
            try (var files = Files.walk(classes)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    jar.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
                    Files.copy(file, jar);
                    jar.closeEntry();
                }
            }
            descriptor(jar, "fixture.ExampleFactory");
        }
        try (var loaded = new PluginLoader().load(plugins)) {
            assertEquals(1, loaded.backendFactories().size());
            var factory = loaded.backendFactories().getFirst();
            assertEquals("example", factory.type());
            assertNotSame(BackendFactory.class.getClassLoader(), factory.getClass().getClassLoader());
            try (var first = factory.create("analytics", Map.of());
                 var second = factory.create("warehouse", Map.of())) {
                assertEquals("analytics", first.id());
                assertEquals("warehouse", second.id());
                first.prepareTransaction(PactState.empty(), PactState.empty()).apply();
                second.apply(PactState.empty());
            }
        }
        Files.delete(plugins.resolve("example.jar")); // Closing releases the JAR on Windows.
    }

    @Test
    void reportsBrokenServiceDescriptorAsPluginLoadFailureAndReleasesJar() throws Exception {
        Path jarPath = directory.resolve("broken.jar");
        try (var jar = new JarOutputStream(Files.newOutputStream(jarPath))) {
            descriptor(jar, "missing.Factory");
        }
        assertThrows(PluginLoadException.class, () -> new PluginLoader().load(directory));
        Files.delete(jarPath);
    }

    private static void descriptor(JarOutputStream jar, String implementation) throws Exception {
        jar.putNextEntry(new JarEntry("META-INF/services/" + BackendFactory.class.getName()));
        jar.write((implementation + "\n").getBytes(StandardCharsets.UTF_8));
        jar.closeEntry();
    }
}
