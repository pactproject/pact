package io.github.pactproject.postgresql.compile;

import io.github.pactproject.api.Access;
import io.github.pactproject.api.PactState;
import io.github.pactproject.api.Resource;
import io.github.pactproject.api.value.Value;
import io.github.pactproject.postgresql.model.DefaultPrivilegeGrant;
import io.github.pactproject.postgresql.model.DefaultPrivilegeOverride;
import io.github.pactproject.postgresql.model.DefaultPrivilegeScope;
import io.github.pactproject.postgresql.model.DefaultPrivilegeType;
import io.github.pactproject.postgresql.model.Grant;
import io.github.pactproject.postgresql.model.GrantTarget;
import io.github.pactproject.postgresql.model.RoutineSignature;
import io.github.pactproject.postgresql.model.Privilege;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GrantCompilerTest {
    @Test
    void compilesDatabaseGrantsAndNormalizesPrivilegeNames() {
        PactState state = state(access(
                "alice",
                Map.of("database", "analytics"),
                Map.of("database", Set.of("connect", "CREATE", "TEMP"))
        ));

        GrantTarget db = new GrantTarget("analytics", null, null, null);
        assertEquals(
                Set.of(
                        new Grant(db, "alice", Privilege.CONNECT),
                        new Grant(db, "alice", Privilege.CREATE),
                        new Grant(db, "alice", Privilege.TEMPORARY)
                ),
                GrantCompiler.compile(state, "postgres")
        );
    }

    @Test
    void compilesEachPermissionLevelOnTheTruncatedTarget() {
        PactState state = state(access(
                "alice",
                Map.of(
                        "database", "analytics",
                        "schema", "sales",
                        "table", "orders",
                        "column", "email"
                ),
                Map.of(
                        "database", Set.of("CONNECT"),
                        "schema", Set.of("USAGE"),
                        "table", Set.of("SELECT"),
                        "column", Set.of("UPDATE")
                )
        ));

        assertEquals(
                Set.of(
                        new Grant(
                                new GrantTarget("analytics", null, null, null),
                                "alice", Privilege.CONNECT),
                        new Grant(
                                new GrantTarget("analytics", "sales", null, null),
                                "alice", Privilege.USAGE),
                        new Grant(
                                new GrantTarget("analytics", "sales", "orders", null),
                                "alice", Privilege.SELECT),
                        new Grant(
                                new GrantTarget("analytics", "sales", "orders", "email"),
                                "alice", Privilege.UPDATE)
                ),
                GrantCompiler.compile(state, "postgres")
        );
    }

    @Test
    void compilesTableAndSequenceBranchesFromOneResource() {
        PactState state = state(access(
                "alice",
                Map.of(
                        "database", "analytics",
                        "schema", "public",
                        "table", "orders",
                        "column", "id",
                        "sequence", "orders_id_seq"
                ),
                Map.of(
                        "schema", Set.of("USAGE"),
                        "table", Set.of("SELECT"),
                        "column", Set.of("UPDATE"),
                        "sequence", Set.of("USAGE", "SELECT")
                )
        ));

        assertEquals(
                Set.of(
                        new Grant(
                                new GrantTarget(
                                        "analytics", "public", null, null),
                                "alice", Privilege.USAGE),
                        new Grant(
                                new GrantTarget(
                                        "analytics", "public", "orders", null),
                                "alice", Privilege.SELECT),
                        new Grant(
                                new GrantTarget(
                                        "analytics", "public", "orders", "id"),
                                "alice", Privilege.UPDATE),
                        new Grant(
                                new GrantTarget(
                                        "analytics", "public", null, null,
                                        "orders_id_seq"),
                                "alice", Privilege.USAGE),
                        new Grant(
                                new GrantTarget(
                                        "analytics", "public", null, null,
                                        "orders_id_seq"),
                                "alice", Privilege.SELECT)
                ),
                GrantCompiler.compile(state, "postgres")
        );
    }

    @Test
    void compilesFunctionOverloadAsASeparateSchemaBranch() {
        PactState state = state(access(
                "alice",
                Map.of(
                        "database", "analytics",
                        "schema", "public",
                        "table", "orders",
                        "function", "add(integer, integer)"
                ),
                Map.of(
                        "table", Set.of("SELECT"),
                        "function", Set.of("EXECUTE")
                )
        ));

        assertEquals(
                Set.of(
                        new Grant(
                                new GrantTarget(
                                        "analytics", "public", "orders", null),
                                "alice", Privilege.SELECT),
                        new Grant(
                                new GrantTarget(
                                        "analytics", "public", null, null, null,
                                        new RoutineSignature(
                                                "add",
                                                "integer, integer"
                                        )
                                ),
                                "alice", Privilege.EXECUTE)
                ),
                GrantCompiler.compile(state, "postgres")
        );
    }

    @Test
    void compilesProcedureSignatureAsItsOwnSchemaBranch() {
        PactState state = state(access(
                "alice",
                Map.of(
                        "database", "analytics",
                        "schema", "public",
                        "table", "orders",
                        "procedure", "refresh_orders(date)"
                ),
                Map.of(
                        "table", Set.of("SELECT"),
                        "procedure", Set.of("EXECUTE")
                )
        ));

        assertEquals(
                Set.of(
                        new Grant(
                                new GrantTarget(
                                        "analytics", "public", "orders", null),
                                "alice", Privilege.SELECT),
                        new Grant(
                                new GrantTarget(
                                        "analytics", "public", null, null,
                                        null, null,
                                        new RoutineSignature("refresh_orders", "date")
                                ),
                                "alice", Privilege.EXECUTE)
                ),
                GrantCompiler.compile(state, "postgres")
        );
    }

    @Test
    void compilesDefaultPrivilegesAndSpecificOverrides() {
        Access defaults = new Access(
                "alice",
                new Resource("postgres", Map.of(
                        "database", "analytics",
                        "schema", "sales"
                )),
                Map.of(
                        "permissions", permissions(Map.of(
                                "schema", Set.of("USAGE")
                        )),
                        "defaultPrivileges", Value.object(Map.of(
                                "creator", Value.string("sales_migrator"),
                                "permissions", permissions(Map.of(
                                        "table", Set.of("SELECT", "UPDATE"),
                                        "sequence", Set.of("USAGE", "SELECT"),
                                        "function", Set.of("EXECUTE")
                                ))
                        ))
                )
        );
        Access tableOverride = access(
                "alice",
                Map.of(
                        "database", "analytics",
                        "schema", "sales",
                        "table", "private_orders"
                ),
                Map.of("table", Set.of("SELECT"))
        );

        var compiled = GrantCompiler.compileState(
                state(defaults, tableOverride),
                "postgres"
        );
        DefaultPrivilegeScope tables = new DefaultPrivilegeScope(
                "analytics", "sales", "sales_migrator",
                DefaultPrivilegeType.TABLES
        );
        assertEquals(
                Set.of(
                        new Grant(
                                new GrantTarget("analytics", "sales", null, null),
                                "alice", Privilege.USAGE
                        ),
                        new Grant(
                                new GrantTarget(
                                        "analytics", "sales",
                                        "private_orders", null
                                ),
                                "alice", Privilege.SELECT
                        )
                ),
                compiled.grants()
        );
        assertEquals(
                Set.of(
                        new DefaultPrivilegeGrant(
                                tables, "alice", Privilege.SELECT
                        ),
                        new DefaultPrivilegeGrant(
                                tables, "alice", Privilege.UPDATE
                        ),
                        new DefaultPrivilegeGrant(
                                new DefaultPrivilegeScope(
                                        "analytics", "sales", "sales_migrator",
                                        DefaultPrivilegeType.SEQUENCES
                                ),
                                "alice", Privilege.USAGE
                        ),
                        new DefaultPrivilegeGrant(
                                new DefaultPrivilegeScope(
                                        "analytics", "sales", "sales_migrator",
                                        DefaultPrivilegeType.SEQUENCES
                                ),
                                "alice", Privilege.SELECT
                        ),
                        new DefaultPrivilegeGrant(
                                new DefaultPrivilegeScope(
                                        "analytics", "sales", "sales_migrator",
                                        DefaultPrivilegeType.ROUTINES
                                ),
                                "alice", Privilege.EXECUTE
                        )
                ),
                compiled.defaultPrivileges()
        );
        assertEquals(
                Set.of(
                        tables,
                        new DefaultPrivilegeScope(
                                "analytics", "sales", "sales_migrator",
                                DefaultPrivilegeType.SEQUENCES
                        ),
                        new DefaultPrivilegeScope(
                                "analytics", "sales", "sales_migrator",
                                DefaultPrivilegeType.ROUTINES
                        )
                ),
                compiled.defaultPrivilegeScopes()
        );
        assertEquals(
                Set.of(new DefaultPrivilegeOverride(
                        new GrantTarget(
                                "analytics", "sales", "private_orders", null
                        ),
                        "alice",
                        DefaultPrivilegeType.TABLES
                )),
                compiled.defaultPrivilegeOverrides()
        );
    }

    @Test
    void findsTargetDatabaseScope() {
        PactState state = state(
                access("alice", Map.of("database", "analytics"),
                        Map.of("database", Set.of("CONNECT"))),
                access("bob", Map.of("database", "reporting", "schema", "s"),
                        Map.of("schema", Set.of("USAGE")))
        );

        assertEquals(
                Set.of("analytics", "reporting"),
                GrantCompiler.databases(state, "postgres")
        );
    }

    @Test
    void rejectsPrivilegesOnTheWrongLevel() {
        assertInvalid(access(
                "alice",
                Map.of("database", "analytics"),
                Map.of("database", Set.of("SELECT"))
        ));
        assertInvalid(access(
                "alice",
                Map.of("database", "a", "schema", "s", "table", "t"),
                Map.of("table", Set.of("USAGE"))
        ));
        assertInvalid(access(
                "alice",
                Map.of("database", "a", "schema", "s", "table", "t", "column", "c"),
                Map.of("column", Set.of("DELETE"))
        ));
    }

    @Test
    void rejectsPermissionLevelsDeeperThanTarget() {
        assertInvalid(access(
                "alice",
                Map.of("database", "analytics"),
                Map.of("table", Set.of("SELECT"))
        ));
        assertInvalid(access(
                "alice",
                Map.of("database", "analytics"),
                Map.of("unknown", Set.of("SELECT"))
        ));
    }

    @Test
    void rejectsMalformedTargets() {
        Map<String, Set<String>> none = Map.of();
        assertInvalid(access("alice", Map.of("schema", "s"), none));
        assertInvalid(access(
                "alice", Map.of("database", "a", "table", "t"), none));
        assertInvalid(access(
                "alice", Map.of("database", "a", "schema", "s", "column", "c"), none));
        assertInvalid(access(
                "alice", Map.of("database", "a", "sequence", "seq"), none));
        assertInvalid(access(
                "alice",
                Map.of("database", "a", "schema", "s", "function", "add"),
                none
        ));
        assertInvalid(access("alice", Map.of("database", "*"), none));
        assertInvalid(access(
                "alice",
                Map.of("database", "a", "schema", "*", "table", "*"),
                Map.of("table", Set.of("SELECT"))
        ));
        assertInvalid(access(
                "alice", Map.of("database", "a", "bucket", "b"), none));
    }

    @Test
    void rejectsUnsupportedAttributesAndBackendIds() {
        assertThrows(
                IllegalArgumentException.class,
                () -> GrantCompiler.compile(
                        new PactState(Set.of(new Access(
                                "alice",
                                new Resource("postgres", Map.of("database", "analytics")),
                                Map.of("rowPolicy", Value.string("true"))
                        ))),
                        "postgres"
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> GrantCompiler.compile(
                        state(access(
                                "alice",
                                Map.of("database", "analytics"),
                                Map.of("database", Set.of("CONNECT"))
                        )),
                        "another-backend"
                )
        );
    }

    private static void assertInvalid(Access access) {
        assertThrows(
                IllegalArgumentException.class,
                () -> GrantCompiler.compile(state(access), "postgres")
        );
    }

    private static Access access(
            String role,
            Map<String, String> target,
            Map<String, Set<String>> permissions
    ) {
        return new Access(
                role,
                new Resource("postgres", target),
                Map.of("permissions", permissions(permissions))
        );
    }

    private static Value permissions(Map<String, Set<String>> values) {
        return Value.object(values.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> Value.set(entry.getValue().stream()
                                .map(Value::string)
                                .collect(Collectors.toSet()))
                )));
    }

    private static PactState state(Access... accesses) {
        return new PactState(Set.of(accesses));
    }
}
