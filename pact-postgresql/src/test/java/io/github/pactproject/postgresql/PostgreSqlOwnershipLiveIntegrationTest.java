package io.github.pactproject.postgresql;

import io.github.pactproject.api.Access;
import io.github.pactproject.api.Identity;
import io.github.pactproject.api.PactState;
import io.github.pactproject.api.Resource;
import io.github.pactproject.api.value.Value;
import io.github.pactproject.api.exception.BackendOperationException;
import io.github.pactproject.core.PactCore;
import io.github.pactproject.core.exception.ApplyException;
import io.github.pactproject.filesystem.FileSystemStateProvider;
import io.github.pactproject.kubernetes.compile.DataAccessCompiler;
import io.github.pactproject.postgresql.api.PostgreSqlClient;
import io.github.pactproject.postgresql.api.PostgreSqlClientException;
import io.github.pactproject.postgresql.client.JdbcPostgreSqlClient;
import io.github.pactproject.postgresql.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "pact.integration", matches = "true")
class PostgreSqlOwnershipLiveIntegrationTest {
    private static final String ID = "application-db";
    @TempDir Path directory;

    @Test
    void bothProvidersAssignExactOwnersAndEmptyStateRetainsOwnership() throws Exception {
        try (var fixture = new Fixture()) {
            Path file = directory.resolve("ownership.yaml");
            Files.writeString(file, """
                    accesses:
                      - principal: %s
                        resource:
                          backendId: application-db
                          target: {database: %s, schema: sales, table: orders}
                        attributes:
                          ownership: [schema, table]
                    """.formatted(fixture.a, fixture.database));
            try (var core = new PactCore(List.of(fixture.backend()))) {
                var provider = new FileSystemStateProvider(file);
                core.apply(provider.load());
                fixture.assertOwner("table", "orders", fixture.a);
                fixture.assertOwner("sequence", "orders_id_seq", fixture.a);
                fixture.assertOwner("sequence", "orders_identity_id_seq", fixture.a);
                fixture.assertOwner("table", "unrelated", fixture.config.username());
                fixture.assertOwner("sequence", "free_seq", fixture.config.username());
                fixture.execute("CREATE TABLE sales.future_child (id integer)");
                fixture.assertOwner("table", "future_child", fixture.config.username());
                Files.writeString(file, "accesses: []\n");
                core.apply(provider.load());
                fixture.assertOwner("table", "orders", fixture.a);

                var spec = Map.of("resources", List.of(Map.of(ID,
                        Map.of("database", fixture.database, "schema", "sales", "table", List.of("orders", "orders_view", "orders_mv", "parts"),
                                "sequence", "free_seq", "function", "echo(integer)", "procedure", "run()"),
                        "access", List.of(Map.of("users", List.of(fixture.b), "ownership",
                                List.of("database", "schema", "table", "sequence", "function", "procedure"))))));
                var desired = new DataAccessCompiler().compile(List.of(spec));
                core.apply(desired);
                String objectVersion = fixture.scalar("SELECT xmin::text FROM pg_class WHERE oid='sales.orders'::regclass");
                fixture.backend().prepareTransaction(desired, desired).apply();
                assertEquals(objectVersion, fixture.scalar("SELECT xmin::text FROM pg_class WHERE oid='sales.orders'::regclass"),
                        "Matching owners must not rewrite the object catalog");
                for (var entry : Map.of("table", "orders", "sequence", "free_seq",
                        "function", "echo(integer)", "procedure", "run()",
                        "schema", "sales", "database", fixture.database).entrySet()) {
                    fixture.assertOwner(entry.getKey(), entry.getValue(), fixture.b);
                }
                fixture.assertOwner("function", "echo(text)", fixture.config.username());
                for (String name : List.of("orders_view", "orders_mv", "parts")) {
                    fixture.assertOwner("table", name, fixture.b);
                }
                fixture.assertOwner("table", "part_one", fixture.config.username());
                // Ownership confers real DDL authority even without explicit table grants.
                fixture.execute("SET ROLE " + quote(fixture.b), "ALTER TABLE sales.orders ADD COLUMN owned_ddl integer");
                core.apply(PactState.empty());
                fixture.assertOwner("table", "orders", fixture.b);
            }
        }
    }

    @Test
    void validatesMissingReferencesAliasesAndDependenciesBeforeIdentityWrites() throws Exception {
        try (var fixture = new Fixture()) {
            var valid = fixture.ownership(fixture.a, Map.of("table", "orders"), "table");
            var missing = fixture.ownership(fixture.a, Map.of("table", "absent"), "table");
            String newRole = fixture.database + "_ensured";
            try {
                var invalid = new PactState(Set.of(valid, missing), Set.of(new Identity(ID, newRole, true, null, null, null)));
                assertThrows(BackendOperationException.class,
                        () -> fixture.backend().prepareTransaction(PactState.empty(), invalid));
                assertEquals("0", fixture.scalar("SELECT count(*)::text FROM pg_catalog.pg_roles WHERE rolname='" + newRole + "'"));
                fixture.assertOwner("table", "orders", fixture.config.username());

                for (Set<Access> accesses : List.of(
                        Set.of(fixture.ownership(fixture.a, Map.of("function", "echo(integer)"), "function"),
                                fixture.ownership(fixture.b, Map.of("function", "echo(int4)"), "function")),
                        Set.of(valid, fixture.ownership(fixture.b, Map.of("sequence", "orders_id_seq"), "sequence")))) {
                    assertThrows(BackendOperationException.class,
                            () -> fixture.backend().prepareTransaction(PactState.empty(), new PactState(accesses)));
                    fixture.assertOwner("table", "orders", fixture.config.username());
                }
                var grantor = new JdbcPostgreSqlClient(new PostgreSqlConfig(fixture.config.jdbcUrl(),
                        fixture.config.username(), fixture.config.password(), PostgreSqlReconciliationMode.GRANTOR));
                assertThrows(BackendOperationException.class,
                        () -> new PostgreSqlBackend(ID, grantor).prepareTransaction(PactState.empty(), new PactState(Set.of(valid))));
            } finally {
                fixture.executeAdmin("DROP ROLE IF EXISTS " + quote(newRole));
            }
        }
    }

    @Test
    void defaultsUseFinalOwnerAndNewOwnerMayBeEnsuredExplicitly() throws Exception {
        try (var fixture = new Fixture()) {
            String ensured = fixture.database + "_ensured";
            try {
                var owned = fixture.ownership(ensured, Map.of("table", "orders"), "table");
                var defaults = new Access(fixture.a,
                        new Resource(ID, Map.of("database", fixture.database, "schema", "sales")),
                        Map.of("defaultPrivileges", Value.object(Map.of(
                                "creator", Value.string(ensured),
                                "permissions", Value.object(Map.of("table", Value.set(Set.of(Value.string("SELECT")))))))));
                var desired = new PactState(Set.of(owned, defaults), Set.of(new Identity(ID, ensured, true, null, null, null)));
                fixture.backend().prepareTransaction(PactState.empty(), desired).apply();
                fixture.assertOwner("table", "orders", ensured);
                assertEquals("true", fixture.scalar("SELECT has_table_privilege('" + fixture.a + "', 'sales.orders', 'SELECT')::text"));
                var changed = new PactState(Set.of(fixture.ownership(fixture.b, Map.of("table", "orders"), "table"), defaults));
                fixture.backend().prepareTransaction(desired, changed).apply();
                assertEquals("false", fixture.scalar("SELECT has_table_privilege('" + fixture.a + "', 'sales.orders', 'SELECT')::text"));
                assertEquals("false", fixture.scalar("SELECT has_schema_privilege('" + fixture.b + "', 'sales', 'USAGE')::text"),
                        "ACL revocation must not retain temporary schema access for the new owner");
                fixture.backend().prepareTransaction(changed, PactState.empty()).apply();
            } finally {
                // Drop database first so the ensured role has no owned objects/default ACLs.
                fixture.executeAdmin("DROP DATABASE " + quote(fixture.database));
                fixture.executeAdmin("DROP ROLE IF EXISTS " + quote(ensured));
            }
        }
    }

    @Test
    void lostCommitResponseRestoresDeclaredOwnerButRetainsFirstAssignmentAndStartupReapplies() throws Exception {
        try (var fixture = new Fixture()) {
            var fail = new AtomicBoolean();
            var client = new FaultingClient(fixture.client, fail);
            var previous = new PactState(Set.of(fixture.ownership(fixture.a, Map.of("table", "orders"), "table")));
            var desired = new PactState(Set.of(fixture.ownership(fixture.b, Map.of("table", "orders"), "table")));
            try (var core = new PactCore(List.of(new PostgreSqlBackend(ID, client)))) {
                core.apply(previous);
                fail.set(true);
                assertThrows(ApplyException.class, () -> core.apply(desired));
                fixture.assertOwner("table", "orders", fixture.a);
                core.apply(desired);
                fixture.assertOwner("table", "orders", fixture.b);
            }
            try (var firstAssignment = new PactCore(List.of(new PostgreSqlBackend(ID, client)))) {
                fail.set(true);
                assertThrows(ApplyException.class, () -> firstAssignment.apply(previous));
                fixture.assertOwner("table", "orders", fixture.a);
            }
            fixture.execute("ALTER TABLE sales.orders OWNER TO " + quote(fixture.b));
            try (var restarted = new PactCore(List.of(fixture.backend()))) {
                restarted.restoreAppliedState(previous);
                restarted.apply(previous);
                fixture.assertOwner("table", "orders", fixture.a);
            }
        }
    }

    @Test
    void compensatesOwnerChangesAcrossDatabasesAfterFirstCommitAndAllowsRetry() throws Exception {
        try (var first = new Fixture(); var second = new Fixture()) {
            var previous = new PactState(Set.of(first.ownership(first.a, Map.of("table", "orders"), "table"),
                    second.ownership(second.a, Map.of("table", "orders"), "table")));
            var desired = new PactState(Set.of(first.ownership(first.b, Map.of("table", "orders"), "table"),
                    second.ownership(second.b, Map.of("table", "orders"), "table")));
            var fail = new AtomicBoolean();
            try (var core = new PactCore(List.of(new PostgreSqlBackend(ID, new FaultingClient(first.client, fail))))) {
                core.apply(previous);
                fail.set(true);
                assertThrows(ApplyException.class, () -> core.apply(desired));
                assertFalse(fail.get(), "Fault must follow a real committed database update");
                first.assertOwner("table", "orders", first.a);
                second.assertOwner("table", "orders", second.a);
                core.apply(desired);
                first.assertOwner("table", "orders", first.b);
                second.assertOwner("table", "orders", second.b);
            }
        }
    }

    @Test
    void securityDefinerRunsWithExplicitlyDeclaredOwner() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.execute("CREATE FUNCTION sales.who() RETURNS name LANGUAGE sql SECURITY DEFINER AS 'SELECT current_user'");
            var state = new PactState(Set.of(fixture.ownership(fixture.a, Map.of("function", "who()"), "function")));
            fixture.backend().prepareTransaction(PactState.empty(), state).apply();
            assertEquals(fixture.a, fixture.scalar("SELECT sales.who()::text"));
        }
    }

    private record FaultingClient(JdbcPostgreSqlClient delegate, AtomicBoolean fail) implements PostgreSqlClient {
        public void validateReferences(Set<Grant> grants, Set<DefaultPrivilegeGrant> defaults, Set<Identity> identities)
                throws PostgreSqlClientException {
            delegate.validateReferences(grants, defaults, identities);
        }
        public Set<Grant> getManagedGrants(Set<String> databases) throws PostgreSqlClientException {
            return delegate.getManagedGrants(databases);
        }
        public Set<DefaultPrivilegeGrant> getManagedDefaultPrivileges(Set<DefaultPrivilegeScope> scopes) throws PostgreSqlClientException {
            return delegate.getManagedDefaultPrivileges(scopes);
        }
        public void validateOwnerships(Set<Ownership> desired, Set<String> ensured) throws PostgreSqlClientException {
            delegate.validateOwnerships(desired, ensured);
        }
        public void reconcileIdentities(Set<Identity> previous, Set<Identity> desired) throws PostgreSqlClientException {
            delegate.reconcileIdentities(previous, desired);
        }
        public void synchronize(Set<String> databases, Set<Grant> desired, Set<DefaultPrivilegeGrant> defaults,
                Set<DefaultPrivilegeScope> scopes, Set<DefaultPrivilegeOverride> overrides) throws PostgreSqlClientException {
            synchronize(databases, desired, defaults, scopes, overrides, Set.of());
        }
        public void synchronize(Set<String> databases, Set<Grant> desired, Set<DefaultPrivilegeGrant> defaults,
                Set<DefaultPrivilegeScope> scopes, Set<DefaultPrivilegeOverride> overrides, Set<Ownership> owners)
                throws PostgreSqlClientException {
            for (String database : new java.util.TreeSet<>(databases)) {
                delegate.synchronize(Set.of(database), desired.stream().filter(value -> value.database().equals(database))
                                .collect(java.util.stream.Collectors.toSet()),
                        defaults.stream().filter(value -> value.scope().database().equals(database))
                                .collect(java.util.stream.Collectors.toSet()),
                        scopes.stream().filter(value -> value.database().equals(database))
                                .collect(java.util.stream.Collectors.toSet()),
                        overrides.stream().filter(value -> value.target().database().equals(database))
                                .collect(java.util.stream.Collectors.toSet()),
                        owners.stream().filter(value -> value.target().database().equals(database))
                                .collect(java.util.stream.Collectors.toSet()));
                if (fail.compareAndSet(true, false)) throw new PostgreSqlClientException("Injected lost commit response");
            }
        }
    }

    private static final class Fixture implements AutoCloseable {
        final PostgreSqlConfig config = new PostgreSqlConfig(required("PACT_IT_POSTGRESQL_JDBC_URL"),
                required("PACT_IT_POSTGRESQL_USERNAME"), required("PACT_IT_POSTGRESQL_PASSWORD"));
        final String database = "pact_ownership_" + UUID.randomUUID().toString().replace("-", "");
        final String a = database + "_a";
        final String b = database + "_b";
        final JdbcPostgreSqlClient client = new JdbcPostgreSqlClient(config);

        Fixture() throws Exception {
            executeAdmin("CREATE DATABASE " + quote(database), "CREATE ROLE " + quote(a) + " NOLOGIN",
                    "CREATE ROLE " + quote(b) + " NOLOGIN");
            execute("CREATE SCHEMA sales", "CREATE TABLE sales.orders (id serial, identity_id bigint GENERATED ALWAYS AS IDENTITY)",
                    "CREATE TABLE sales.unrelated (id integer)", "CREATE SEQUENCE sales.free_seq",
                    "CREATE VIEW sales.orders_view AS SELECT id FROM sales.orders",
                    "CREATE MATERIALIZED VIEW sales.orders_mv AS SELECT id FROM sales.orders",
                    "CREATE TABLE sales.parts (id integer) PARTITION BY RANGE (id)",
                    "CREATE TABLE sales.part_one PARTITION OF sales.parts FOR VALUES FROM (0) TO (10)",
                    "CREATE FUNCTION sales.echo(integer) RETURNS integer LANGUAGE sql AS 'SELECT $1'",
                    "CREATE FUNCTION sales.echo(text) RETURNS text LANGUAGE sql AS 'SELECT $1'",
                    "CREATE PROCEDURE sales.run() LANGUAGE sql AS 'SELECT 1'");
        }
        PostgreSqlBackend backend() { return new PostgreSqlBackend(ID, client); }
        Access ownership(String owner, Map<String, String> fields, String... levels) {
            var target = new java.util.HashMap<>(fields);
            target.put("database", database);
            target.put("schema", "sales");
            var values = new HashSet<Value>();
            for (String level : levels) values.add(Value.string(level));
            return new Access(owner, new Resource(ID, target), Map.of("ownership", Value.set(values)));
        }
        Connection connection() throws Exception {
            var source = new PGSimpleDataSource();
            source.setUrl(config.jdbcUrl());
            source.setDatabaseName(database);
            return source.getConnection(config.username(), config.password());
        }
        void execute(String... commands) throws Exception {
            try (var connection = connection(); var statement = connection.createStatement()) {
                for (String sql : commands) statement.execute(sql);
            }
        }
        void executeAdmin(String... commands) throws Exception {
            try (var connection = DriverManager.getConnection(config.jdbcUrl(), config.username(), config.password());
                    var statement = connection.createStatement()) {
                for (String sql : commands) statement.execute(sql);
            }
        }
        String scalar(String sql) throws Exception {
            try (var connection = connection(); var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
                assertTrue(rows.next());
                String result = rows.getString(1);
                assertFalse(rows.next());
                return result;
            }
        }
        void assertOwner(String type, String name, String expected) throws Exception {
            String sql = switch (type) {
                case "database" -> "SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname=current_database()";
                case "schema" -> "SELECT pg_get_userbyid(nspowner) FROM pg_namespace WHERE nspname='sales'";
                case "function", "procedure" -> "SELECT pg_get_userbyid(proowner) FROM pg_proc WHERE oid='sales." + name + "'::regprocedure";
                default -> "SELECT pg_get_userbyid(relowner) FROM pg_class WHERE oid='sales." + name + "'::regclass";
            };
            assertEquals(expected, scalar(sql), type + " " + name);
        }
        public void close() throws Exception {
            executeAdmin("DROP DATABASE IF EXISTS " + quote(database), "DROP ROLE IF EXISTS " + quote(a), "DROP ROLE IF EXISTS " + quote(b));
        }
    }
    private static String quote(String name) { return "\"" + name.replace("\"", "\"\"") + "\""; }
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing " + name);
        return value;
    }
}
