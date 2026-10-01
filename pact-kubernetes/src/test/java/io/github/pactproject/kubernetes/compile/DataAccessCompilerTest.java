package io.github.pactproject.kubernetes.compile;

import io.github.pactproject.api.Access;
import io.github.pactproject.api.PactState;
import io.github.pactproject.api.value.BooleanValue;
import io.github.pactproject.api.value.ObjectValue;
import io.github.pactproject.api.value.SetValue;
import io.github.pactproject.api.value.StringValue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataAccessCompilerTest
{
    private final DataAccessCompiler compiler =
            new DataAccessCompiler();

    @Test
    void compilesResourceAndUsers()
    {
        PactState state = compiler.compile(
                List.of(
                        Map.of(
                                "resources",
                                List.of(
                                        Map.of(
                                                "ozone",
                                                Map.of(
                                                        "volume", "data",
                                                        "bucket", "analytics",
                                                        "isKeyRecursive", true
                                                ),
                                                "access",
                                                List.of(
                                                        Map.of(
                                                                "users",
                                                                List.of(
                                                                        "alice",
                                                                        "bob"
                                                                ),
                                                                "permissions",
                                                                Map.of(
                                                                        "read",
                                                                        true
                                                                )
                                                        )
                                                )
                                        )
                                )
                        )
                )
        );

        assertEquals(2, state.accesses().size());

        Access alice = state.accesses().stream()
                .filter(
                        access -> access.principal().equals("alice")
                )
                .findFirst()
                .orElseThrow();

        assertEquals(
                "ozone",
                alice.resource().backendId()
        );

        assertEquals(
                Map.of(
                        "volume", "data",
                        "bucket", "analytics",
                        "isKeyRecursive", "true"
                ),
                alice.resource().target()
        );

        assertEquals(
                new ObjectValue(
                        Map.of(
                                "read",
                                new BooleanValue(true)
                        )
                ),
                alice.attributes().get("permissions")
        );
    }

    @Test
    void compilesRangerServiceTypesWithoutAServiceAllowList()
    {
        PactState state = compiler.compile(
                List.of(
                        Map.of(
                                "resources",
                                List.of(
                                        Map.of(
                                                "kafka",
                                                Map.of(
                                                        "cluster", "events",
                                                        "topic", "audit"
                                                ),
                                                "access",
                                                List.of(
                                                        Map.of(
                                                                "users",
                                                                List.of("alice"),
                                                                "permissions",
                                                                Map.of(
                                                                        "topic",
                                                                        List.of("consume")
                                                                )
                                                        )
                                                )
                                        )
                                )
                        )
                )
        );

        Access access = state.accesses().iterator().next();
        assertEquals("kafka", access.resource().backendId());
        assertEquals(
                Map.of("cluster", "events", "topic", "audit"),
                access.resource().target()
        );
        assertEquals(
                new ObjectValue(Map.of(
                        "topic",
                        new SetValue(Set.of(new StringValue("consume")))
                )),
                access.attributes().get("permissions")
        );
    }

    @Test
    void compilesMultipleDataAccesses()
    {
        PactState state = compiler.compile(
                List.of(
                        Map.of(
                                "resources",
                                List.of(
                                        Map.of(
                                                "ozone",
                                                Map.of(
                                                        "volume", "data"
                                                ),
                                                "access",
                                                List.of(
                                                        Map.of(
                                                                "users",
                                                                List.of("alice"),
                                                                "permissions",
                                                                Map.of(
                                                                        "read",
                                                                        true
                                                                )
                                                        )
                                                )
                                        )
                                )
                        ),
                        Map.of(
                                "resources",
                                List.of(
                                        Map.of(
                                                "registry",
                                                Map.of(
                                                        "name", "repository-a"
                                                ),
                                                "access",
                                                List.of(
                                                        Map.of(
                                                                "users",
                                                                List.of("bob"),
                                                                "permissions",
                                                                List.of(
                                                                        "read",
                                                                        "write"
                                                                )
                                                        )
                                                )
                                        )
                                )
                        )
                )
        );

        assertEquals(2, state.accesses().size());

        assertTrue(
                state.accesses().stream()
                        .anyMatch(
                                access ->
                                        access.principal().equals("alice")
                                                && access.resource()
                                                .backendId()
                                                .equals("ozone")
                        )
        );

        assertTrue(
                state.accesses().stream()
                        .anyMatch(
                                access ->
                                        access.principal().equals("bob")
                                                && access.resource()
                                                .backendId()
                                                .equals("registry")
                        )
        );
    }

    @Test
    void compilesRegistryPermissionsToArtifactKeeperActions()
    {
        PactState state = compiler.compile(
                List.of(
                        Map.of(
                                "resources",
                                List.of(
                                        Map.of(
                                                "registry",
                                                Map.of("name", "repository-a"),
                                                "access",
                                                List.of(
                                                        Map.of(
                                                                "users",
                                                                List.of("alice"),
                                                                "permissions",
                                                                List.of("read", "write", "read")
                                                        )
                                                )
                                        )
                                )
                        )
                )
        );

        Access access = state.accesses().iterator().next();
        assertEquals(
                Map.of("repository", "repository-a"),
                access.resource().target()
        );
        assertEquals(
                new SetValue(
                        Set.of(
                                new StringValue("read"),
                                new StringValue("write")
                        )
                ),
                access.attributes().get("actions")
        );
    }

    @Test
    void rejectsUnsupportedRegistryFields()
    {
        assertThrows(
                IllegalArgumentException.class,
                () -> compiler.compile(
                        List.of(
                                Map.of(
                                        "resources",
                                        List.of(
                                                Map.of(
                                                        "registry",
                                                        Map.of("name", "repository-a"),
                                                        "access",
                                                        List.of(
                                                                Map.of(
                                                                        "users",
                                                                        List.of("alice"),
                                                                        "denyPermissions",
                                                                        Map.of("delete", true)
                                                                )
                                                        )
                                                )
                                        )
                                )
                        )
                )
        );
    }

    @Test
    void parsesNestedAttributes()
    {
        PactState state = compiler.compile(
                List.of(
                        Map.of(
                                "resources",
                                List.of(
                                        Map.of(
                                                "trino",
                                                Map.of(
                                                        "catalog", "iceberg",
                                                        "schema", "analytics"
                                                ),
                                                "access",
                                                List.of(
                                                        Map.of(
                                                                "users",
                                                                List.of("alice"),
                                                                "conditions",
                                                                List.of(
                                                                        "tenant = 42"
                                                                ),
                                                                "override",
                                                                false,
                                                                "dataMask",
                                                                Map.of(
                                                                        "type",
                                                                        "custom",
                                                                        "expression",
                                                                        "sha256(value)"
                                                                )
                                                        )
                                                )
                                        )
                                )
                        )
                )
        );

        Access access =
                state.accesses().iterator().next();

        assertEquals(
                new SetValue(
                        Set.of(
                                new StringValue("tenant = 42")
                        )
                ),
                access.attributes().get("conditions")
        );

        assertEquals(
                new BooleanValue(false),
                access.attributes().get("override")
        );

        assertEquals(
                new ObjectValue(
                        Map.of(
                                "type",
                                new StringValue("custom"),
                                "expression",
                                new StringValue("sha256(value)")
                        )
                ),
                access.attributes().get("dataMask")
        );
    }

    @Test
    void ignoresAccessWithoutUsers()
    {
        PactState state = compiler.compile(
                List.of(
                        Map.of(
                                "resources",
                                List.of(
                                        Map.of(
                                                "registry",
                                                Map.of(
                                                        "name", "repository-a"
                                                ),
                                                "access",
                                                List.of(
                                                        Map.of(
                                                                "permissions",
                                                                List.of("read")
                                                        )
                                                )
                                        )
                                )
                        )
                )
        );

        assertTrue(
                state.accesses().isEmpty()
        );
    }

    @Test
    void ignoresRegistryAccessWithoutPermissions()
    {
        PactState state = compiler.compile(
                List.of(
                        Map.of(
                                "resources",
                                List.of(
                                        Map.of(
                                                "registry",
                                                Map.of(
                                                        "name", "repository-a"
                                                ),
                                                "access",
                                                List.of(
                                                        Map.of(
                                                                "users",
                                                                List.of("alice")
                                                        )
                                                )
                                        )
                                )
                        )
                )
        );

        assertTrue(state.accesses().isEmpty());
    }

    @Test
    void deduplicatesUsersAndAccesses()
    {
        PactState state = compiler.compile(
                List.of(
                        Map.of(
                                "resources",
                                List.of(
                                        Map.of(
                                                "registry",
                                                Map.of(
                                                        "name", "repository-a"
                                                ),
                                                "access",
                                                List.of(
                                                        Map.of(
                                                                "users",
                                                                List.of(
                                                                        "alice",
                                                                        "alice"
                                                                ),
                                                                "permissions",
                                                                List.of("read")
                                                        ),
                                                        Map.of(
                                                                "users",
                                                                List.of("alice"),
                                                                "permissions",
                                                                List.of("read")
                                                        )
                                                )
                                        )
                                )
                        )
                )
        );

        assertEquals(
                1,
                state.accesses().size()
        );
    }

    @Test
    void rejectsMultipleServiceFields()
    {
        assertThrows(
                IllegalArgumentException.class,
                () -> compiler.compile(
                        List.of(
                                Map.of(
                                        "resources",
                                        List.of(
                                                Map.of(
                                                        "ozone",
                                                        Map.of(
                                                                "volume",
                                                                "data"
                                                        ),
                                                        "registry",
                                                        Map.of(
                                                                "name",
                                                                "repository-a"
                                                        ),
                                                        "access",
                                                        List.of()
                                                )
                                        )
                                )
                        )
                )
        );
    }

    @Test
    void rejectsNonScalarTarget()
    {
        assertThrows(
                IllegalArgumentException.class,
                () -> compiler.compile(
                        List.of(
                                Map.of(
                                        "resources",
                                        List.of(
                                                Map.of(
                                                        "ozone",
                                                        Map.of(
                                                                "volume",
                                                                List.of(
                                                                        "data"
                                                                )
                                                        ),
                                                        "access",
                                                        List.of()
                                                )
                                        )
                                )
                        )
                )
        );
    }
}
