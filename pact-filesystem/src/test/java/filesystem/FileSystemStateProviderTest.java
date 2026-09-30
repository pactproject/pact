package filesystem;

import io.github.pactproject.api.Access;
import io.github.pactproject.api.PactState;
import io.github.pactproject.api.Resource;
import io.github.pactproject.api.exception.StateProviderException;
import io.github.pactproject.api.value.BooleanValue;
import io.github.pactproject.api.value.NumberValue;
import io.github.pactproject.api.value.ObjectValue;
import io.github.pactproject.api.value.SetValue;
import io.github.pactproject.api.value.StringValue;
import io.github.pactproject.api.value.Value;
import io.github.pactproject.filesystem.FileSystemStateProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FileSystemStateProviderTest
{
    @TempDir
    Path tempDir;

    @Test
    void loadsEmptyState()
            throws Exception
    {
        Path file = writeYaml("""
                accesses: []
                """);

        FileSystemStateProvider provider =
                new FileSystemStateProvider(file);

        PactState state = provider.load();

        assertEquals(PactState.empty(), state);
    }

    @Test
    void loadsAccess()
            throws Exception
    {
        Path file = writeYaml("""
                accesses:
                  - principal: alice
                    resource:
                      backendId: artifact-keeper-prod
                      target:
                        repository: my-repository
                    attributes:
                      actions:
                        - read
                        - write
                """);

        FileSystemStateProvider provider =
                new FileSystemStateProvider(file);

        PactState state = provider.load();

        Access access = state.accesses()
                .stream()
                .findFirst()
                .orElseThrow();

        assertEquals("alice", access.principal());

        assertEquals(
                new Resource(
                        "artifact-keeper-prod",
                        Map.of("repository", "my-repository")
                ),
                access.resource()
        );

        assertEquals(
                Value.set(Set.of(
                        Value.string("read"),
                        Value.string("write")
                )),
                access.attributes().get("actions")
        );
    }

    @Test
    void loadsAllValueTypes()
            throws Exception
    {
        Path file = writeYaml("""
                accesses:
                  - principal: alice
                    resource:
                      backendId: test
                      target:
                        repository: repo
                    attributes:
                      string: hello
                      number: 42.50
                      boolean: true
                      set:
                        - one
                        - two
                      object:
                        nestedString: value
                        nestedNumber: 10
                """);

        FileSystemStateProvider provider =
                new FileSystemStateProvider(file);

        PactState state = provider.load();

        Access access = state.accesses()
                .stream()
                .findFirst()
                .orElseThrow();

        assertEquals(
                new StringValue("hello"),
                access.attributes().get("string")
        );

        assertEquals(
                new NumberValue(new BigDecimal("42.5")),
                access.attributes().get("number")
        );

        assertEquals(
                new BooleanValue(true),
                access.attributes().get("boolean")
        );

        assertEquals(
                new SetValue(Set.of(
                        new StringValue("one"),
                        new StringValue("two")
                )),
                access.attributes().get("set")
        );

        ObjectValue object =
                assertInstanceOf(
                        ObjectValue.class,
                        access.attributes().get("object")
                );

        assertEquals(
                new StringValue("value"),
                object.values().get("nestedString")
        );

        assertEquals(
                new NumberValue(new BigDecimal("10")),
                object.values().get("nestedNumber")
        );
    }

    @Test
    void rejectsMissingPrincipal()
            throws Exception
    {
        Path file = writeYaml("""
                accesses:
                  - resource:
                      backendId: test
                      target:
                        repository: repo
                    attributes: {}
                """);

        FileSystemStateProvider provider =
                new FileSystemStateProvider(file);

        assertThrows(
                StateProviderException.class,
                provider::load
        );
    }

    @Test
    void rejectsNonStringResourceTarget()
            throws Exception
    {
        Path file = writeYaml("""
                accesses:
                  - principal: alice
                    resource:
                      backendId: test
                      target:
                        repository: 123
                    attributes: {}
                """);

        FileSystemStateProvider provider =
                new FileSystemStateProvider(file);

        assertThrows(
                StateProviderException.class,
                provider::load
        );
    }

    @Test
    void rejectsInvalidYaml()
            throws Exception
    {
        Path file = writeYaml("""
                accesses:
                  - principal: alice
                    resource:
                      backendId: [broken
                """);

        FileSystemStateProvider provider =
                new FileSystemStateProvider(file);

        assertThrows(
                StateProviderException.class,
                provider::load
        );
    }

    @Test
    void rejectsMissingFile()
    {
        FileSystemStateProvider provider =
                new FileSystemStateProvider(
                        tempDir.resolve("missing.yaml")
                );

        assertThrows(
                StateProviderException.class,
                provider::load
        );
    }

    private Path writeYaml(String content)
            throws Exception
    {
        Path file = tempDir.resolve(
                "state-" + System.nanoTime() + ".yaml"
        );

        Files.writeString(file, content);

        return file;
    }
}
