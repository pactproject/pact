package io.github.pactproject.postgresql.compile;

import io.github.pactproject.api.Access;
import io.github.pactproject.api.PactState;
import io.github.pactproject.api.Resource;
import io.github.pactproject.api.value.Value;
import io.github.pactproject.postgresql.model.DatabaseGrant;
import io.github.pactproject.postgresql.model.DatabasePrivilege;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DatabaseGrantCompilerTest {
    @Test
    void compilesDatabaseGrantsAndNormalizesPrivilegeNames() {
        PactState state = state(
                access(
                        "alice",
                        "analytics",
                        Set.of("connect", "CREATE", "TEMP")
                )
        );

        assertEquals(
                Set.of(
                        grant("analytics", "alice", DatabasePrivilege.CONNECT),
                        grant("analytics", "alice", DatabasePrivilege.CREATE),
                        grant("analytics", "alice", DatabasePrivilege.TEMPORARY)
                ),
                DatabaseGrantCompiler.compile(state, "postgres")
        );
    }

    @Test
    void mergesDuplicateGrantsAndFindsTargetDatabaseScope() {
        PactState state = state(
                access("alice", "analytics", Set.of("CONNECT")),
                access("alice", "analytics", Set.of("CREATE")),
                access("bob", "reporting", Set.of("TEMPORARY"))
        );

        assertEquals(3, DatabaseGrantCompiler.compile(state, "postgres").size());
        assertEquals(
                Set.of("analytics", "reporting"),
                DatabaseGrantCompiler.databases(state, "postgres")
        );
    }

    @Test
    void rejectsUnsupportedPrivilegesAndAttributes() {
        assertThrows(
                IllegalArgumentException.class,
                () -> DatabaseGrantCompiler.compile(
                        state(access("alice", "analytics", Set.of("SELECT"))),
                        "postgres"
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> DatabaseGrantCompiler.compile(
                        new PactState(Set.of(new Access(
                                "alice",
                                new Resource("postgres", Map.of("database", "analytics")),
                                Map.of("rowPolicy", Value.string("true"))
                        ))),
                        "postgres"
                )
        );
    }

    @Test
    void rejectsUnknownTargetFieldsAndBackendIds() {
        assertThrows(
                IllegalArgumentException.class,
                () -> DatabaseGrantCompiler.compile(
                        new PactState(Set.of(new Access(
                                "alice",
                                new Resource(
                                        "postgres",
                                        Map.of("database", "analytics", "schema", "sales")
                                ),
                                Map.of()
                        ))),
                        "postgres"
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> DatabaseGrantCompiler.compile(
                        state(access("alice", "analytics", Set.of("CONNECT"))),
                        "another-backend"
                )
        );
    }

    private static Access access(
            String role,
            String database,
            Set<String> permissions
    ) {
        return new Access(
                role,
                new Resource("postgres", Map.of("database", database)),
                Map.of(
                        "permissions",
                        Value.object(Map.of(
                                "database",
                                Value.set(permissions.stream()
                                        .map(Value::string)
                                        .collect(Collectors.toSet()))
                        ))
                )
        );
    }

    private static PactState state(Access... accesses) {
        return new PactState(Set.of(accesses));
    }

    private static DatabaseGrant grant(
            String database,
            String role,
            DatabasePrivilege privilege
    ) {
        return new DatabaseGrant(database, role, privilege);
    }
}
