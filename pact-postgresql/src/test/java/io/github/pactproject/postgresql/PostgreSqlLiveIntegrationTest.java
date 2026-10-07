package io.github.pactproject.postgresql;

import io.github.pactproject.api.Access;
import io.github.pactproject.api.Identity;
import io.github.pactproject.api.PactState;
import io.github.pactproject.api.Resource;
import io.github.pactproject.api.SecretValue;
import io.github.pactproject.api.exception.BackendOperationException;
import io.github.pactproject.api.value.Value;
import io.github.pactproject.postgresql.client.JdbcPostgreSqlClient;
import io.github.pactproject.postgresql.model.Grant;
import io.github.pactproject.postgresql.model.GrantTarget;
import io.github.pactproject.postgresql.model.Privilege;
import io.github.pactproject.postgresql.model.RoutineSignature;
import io.github.pactproject.postgresql.model.DefaultPrivilegeGrant;
import io.github.pactproject.postgresql.model.DefaultPrivilegeScope;
import io.github.pactproject.postgresql.model.DefaultPrivilegeType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.postgresql.ds.PGSimpleDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "pact.integration", matches = "true")
class PostgreSqlLiveIntegrationTest {
    private static final String BACKEND_ID = "postgres-it";
    private static final String SCHEMA = "pact_it";
    private static final String TABLE = "probe";
    private static final String COLUMN = "value";
    private static final String SEQUENCE = "probe_id_seq";
    private static final String FUNCTION = "increment";
    private static final String INITIAL_PASSWORD = "PactIt-initial-password";
    private static final String UPDATED_PASSWORD = "PactIt-updated-password";

    @Test
    void reconcilesDatabaseObjectGrantsAndRoleIdentity() throws Exception {
        PostgreSqlConfig config = PostgreSqlConfig.from(Map.of(
                "jdbc-url", required("PACT_IT_POSTGRESQL_JDBC_URL"),
                "username", required("PACT_IT_POSTGRESQL_USERNAME"),
                "password", required("PACT_IT_POSTGRESQL_PASSWORD")
        ));
        JdbcPostgreSqlClient client = new JdbcPostgreSqlClient(config);
        String database = "pact_it_" + UUID.randomUUID().toString()
                .replace("-", "");
        String role = "pact_it_role_" + UUID.randomUUID().toString()
                .replace("-", "");
        String schemaName = SCHEMA;
        PostgreSqlBackend backend = new PostgreSqlBackend(BACKEND_ID, client);

        createDatabase(config, database);
        try {
            createFixture(config, database, schemaName);

            PactState initial = state(
                    database,
                    schemaName,
                    role,
                    INITIAL_PASSWORD,
                    "v1",
                    Set.of("SELECT")
            );
            backend.prepare(PactState.empty(), initial).apply();
            assertEquals(
                    expectedGrants(database, schemaName, role, "SELECT"),
                    client.getManagedGrants(Set.of(database))
            );
            assertCanLogin(config, database, role, INITIAL_PASSWORD);

            PactState updated = state(
                    database,
                    schemaName,
                    role,
                    UPDATED_PASSWORD,
                    "v2",
                    Set.of("INSERT")
            );
            backend.prepare(initial, updated).apply();
            assertEquals(
                    expectedGrants(database, schemaName, role, "INSERT"),
                    client.getManagedGrants(Set.of(database))
            );
            assertCanLogin(config, database, role, UPDATED_PASSWORD);

            backend.prepare(updated, PactState.empty()).apply();
            assertTrue(client.getManagedGrants(Set.of(database)).isEmpty());
        }
        finally {
            dropDatabaseAndRole(config, database, role);
        }
    }

    @Test
    void reconcilesGrantsWithDelegatedAuthorityAndNoElevatedRoleAttributes()
            throws Exception {
        PostgreSqlConfig adminConfig = PostgreSqlConfig.from(Map.of(
                "jdbc-url", required("PACT_IT_POSTGRESQL_JDBC_URL"),
                "username", required("PACT_IT_POSTGRESQL_USERNAME"),
                "password", required("PACT_IT_POSTGRESQL_PASSWORD")
        ));
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String database = "pact_it_grants_" + suffix;
        String owner = "pact_it_owner_" + suffix;
        String grantManager = "pact_it_grantor_" + suffix;
        String appRole = "pact_it_app_" + suffix;
        String grantManagerPassword = "PactIt-" + suffix;
        String appPassword = "PactApp-" + suffix;
        String schema = "managed";

        try {
            createDelegatedGrantFixture(
                    adminConfig,
                    database,
                    owner,
                    grantManager,
                    grantManagerPassword,
                    appRole,
                    appPassword,
                    schema,
                    true
            );

            PostgreSqlConfig grantManagerConfig = PostgreSqlConfig.from(
                    Map.of(
                            "jdbc-url", adminConfig.jdbcUrl(),
                            "username", grantManager,
                            "password", grantManagerPassword
                    )
            );
            JdbcPostgreSqlClient client =
                    new JdbcPostgreSqlClient(grantManagerConfig);
            PostgreSqlBackend backend =
                    new PostgreSqlBackend(BACKEND_ID, client);
            PactState desired = grantsOnlyState(database, schema, appRole);

            assertNoElevatedRoleAttributes(adminConfig, grantManager);
            backend.prepare(PactState.empty(), desired).apply();
            assertEquals(
                    Set.of(
                            grant(
                                    new GrantTarget(database, null, null, null),
                                    appRole,
                                    Privilege.CONNECT
                            ),
                            grant(
                                    new GrantTarget(database, schema, null, null),
                                    appRole,
                                    Privilege.USAGE
                            ),
                            grant(
                                    new GrantTarget(
                                            database, schema, TABLE, null
                                    ),
                                    appRole,
                                    Privilege.SELECT
                            )
                    ),
                    client.getManagedGrants(Set.of(database))
            );
            assertCanSelect(
                    adminConfig,
                    database,
                    appRole,
                    appPassword,
                    schema
            );

            backend.prepare(desired, PactState.empty()).apply();
            assertTrue(client.getManagedGrants(Set.of(database)).isEmpty());
        }
        finally {
            dropDatabaseAndRole(
                    adminConfig,
                    database,
                    owner,
                    grantManager,
                    appRole
            );
        }
    }

    @Test
    void failsWhenPostgreSqlSilentlyIgnoresGrantWithoutGrantOption()
            throws Exception {
        PostgreSqlConfig adminConfig = PostgreSqlConfig.from(Map.of(
                "jdbc-url", required("PACT_IT_POSTGRESQL_JDBC_URL"),
                "username", required("PACT_IT_POSTGRESQL_USERNAME"),
                "password", required("PACT_IT_POSTGRESQL_PASSWORD")
        ));
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String database = "pact_it_denied_" + suffix;
        String owner = "pact_it_owner_" + suffix;
        String grantManager = "pact_it_grantor_" + suffix;
        String appRole = "pact_it_app_" + suffix;
        String grantManagerPassword = "PactIt-" + suffix;
        String appPassword = "PactApp-" + suffix;
        String schema = "managed";

        try {
            createDelegatedGrantFixture(
                    adminConfig,
                    database,
                    owner,
                    grantManager,
                    grantManagerPassword,
                    appRole,
                    appPassword,
                    schema,
                    false
            );

            PostgreSqlConfig grantManagerConfig = PostgreSqlConfig.from(
                    Map.of(
                            "jdbc-url", adminConfig.jdbcUrl(),
                            "username", grantManager,
                            "password", grantManagerPassword
                    )
            );
            PostgreSqlBackend backend = new PostgreSqlBackend(
                    BACKEND_ID,
                    new JdbcPostgreSqlClient(grantManagerConfig)
            );
            PactState desired = grantsOnlyState(database, schema, appRole);

            assertThrows(
                    BackendOperationException.class,
                    () -> backend.prepare(PactState.empty(), desired).apply()
            );
        }
        finally {
            dropDatabaseAndRole(
                    adminConfig,
                    database,
                    owner,
                    grantManager,
                    appRole
            );
        }
    }

    @Test
    void authoritativeModeReconcilesAclAcrossGrantorsAndPreservesPublicDefaults()
            throws Exception {
        PostgreSqlConfig adminConfig = PostgreSqlConfig.from(Map.of(
                "jdbc-url", required("PACT_IT_POSTGRESQL_JDBC_URL"),
                "username", required("PACT_IT_POSTGRESQL_USERNAME"),
                "password", required("PACT_IT_POSTGRESQL_PASSWORD")
        ));
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String database = "pact_it_authoritative_" + suffix;
        String owner = "pact_it_owner_" + suffix;
        String oldGrantor = "pact_it_old_grantor_" + suffix;
        String appRole = "pact_it_app_" + suffix;
        String oldGrantorPassword = "PactOld-" + suffix;
        String appPassword = "PactApp-" + suffix;
        String schema = "managed";

        try {
            createAuthoritativeFixture(
                    adminConfig,
                    database,
                    owner,
                    oldGrantor,
                    oldGrantorPassword,
                    appRole,
                    appPassword,
                    schema
            );

            PostgreSqlConfig authoritativeConfig = PostgreSqlConfig.from(
                    Map.of(
                            "jdbc-url", adminConfig.jdbcUrl(),
                            "username", adminConfig.username(),
                            "password", adminConfig.password(),
                            "reconciliation-mode", "authoritative",
                            "preserve-default-public-privileges", "true"
                    )
            );
            JdbcPostgreSqlClient client =
                    new JdbcPostgreSqlClient(authoritativeConfig);
            PostgreSqlBackend backend =
                    new PostgreSqlBackend(BACKEND_ID, client);
            PactState desired = authoritativeState(
                    database,
                    schema,
                    appRole
            );

            PostgreSqlConfig nonSuperuserAuthoritativeConfig =
                    PostgreSqlConfig.from(Map.of(
                            "jdbc-url", adminConfig.jdbcUrl(),
                            "username", oldGrantor,
                            "password", oldGrantorPassword,
                            "reconciliation-mode", "authoritative"
                    ));
            PostgreSqlBackend nonSuperuserBackend = new PostgreSqlBackend(
                    BACKEND_ID,
                    new JdbcPostgreSqlClient(
                            nonSuperuserAuthoritativeConfig
                    )
            );
            assertThrows(
                    BackendOperationException.class,
                    () -> nonSuperuserBackend.prepare(
                            PactState.empty(),
                            desired
                    )
            );

            backend.prepare(PactState.empty(), desired).apply();
            assertEquals(
                    Set.of(
                            grant(
                                    new GrantTarget(database, null, null, null),
                                    appRole,
                                    Privilege.CONNECT
                            ),
                            grant(
                                    new GrantTarget(database, schema, null, null),
                                    appRole,
                                    Privilege.USAGE
                            ),
                            grant(
                                    new GrantTarget(
                                            database, schema, TABLE, null
                                    ),
                                    appRole,
                                    Privilege.SELECT
                            ),
                            grant(
                                    new GrantTarget(database, schema, null, null),
                                    "PUBLIC",
                                    Privilege.USAGE
                            ),
                            grant(
                                    new GrantTarget(
                                            database, schema, TABLE, null
                                    ),
                                    "PUBLIC",
                                    Privilege.SELECT
                            )
                    ),
                    client.getManagedGrants(Set.of(database))
            );
            assertCanSelect(
                    adminConfig,
                    database,
                    appRole,
                    appPassword,
                    schema
            );
            assertCanSelect(
                    adminConfig,
                    database,
                    oldGrantor,
                    oldGrantorPassword,
                    schema
            );
            assertPublicDefaultsPreserved(
                    adminConfig,
                    database,
                    schema
            );
            assertNoGrantOptions(adminConfig, database, schema);

            backend.prepare(desired, PactState.empty()).apply();
            assertTrue(client.getManagedGrants(Set.of(database)).isEmpty());
            assertPublicDefaultsPreserved(
                    adminConfig,
                    database,
                    schema
            );

            PostgreSqlConfig manageDefaultsConfig =
                    PostgreSqlConfig.from(Map.of(
                            "jdbc-url", adminConfig.jdbcUrl(),
                            "username", adminConfig.username(),
                            "password", adminConfig.password(),
                            "reconciliation-mode", "authoritative",
                            "preserve-default-public-privileges", "false"
                    ));
            JdbcPostgreSqlClient manageDefaultsClient =
                    new JdbcPostgreSqlClient(manageDefaultsConfig);
            PostgreSqlBackend manageDefaultsBackend =
                    new PostgreSqlBackend(BACKEND_ID, manageDefaultsClient);
            manageDefaultsBackend.prepare(
                    PactState.empty(),
                    desired
            ).apply();
            assertPublicDefaultsRevoked(
                    adminConfig,
                    database,
                    schema
            );
            manageDefaultsBackend.prepare(
                    desired,
                    PactState.empty()
            ).apply();
        }
        finally {
            dropDatabaseAndRole(
                    adminConfig,
                    database,
                    owner,
                    oldGrantor,
                    appRole
            );
        }
    }

    @Test
    void authoritativeAdoptionPreservesExistingObjectsAndCanRollback()
            throws Exception {
        PostgreSqlConfig adminConfig = PostgreSqlConfig.from(Map.of(
                "jdbc-url", required("PACT_IT_POSTGRESQL_JDBC_URL"),
                "username", required("PACT_IT_POSTGRESQL_USERNAME"),
                "password", required("PACT_IT_POSTGRESQL_PASSWORD")
        ));
        PostgreSqlConfig authoritativeConfig = PostgreSqlConfig.from(Map.of(
                "jdbc-url", adminConfig.jdbcUrl(),
                "username", adminConfig.username(),
                "password", adminConfig.password(),
                "reconciliation-mode", "authoritative"
        ));
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String database = "pact_it_adopt_" + suffix;
        String owner = "pact_it_adopt_owner_" + suffix;
        String oldGrantor = "pact_it_adopt_grantor_" + suffix;
        String runtime = "pact_it_adopt_runtime_" + suffix;
        String reporter = "pact_it_adopt_reporter_" + suffix;
        String obsoleteRole = "pact_it_adopt_obsolete_" + suffix;
        String salesSchema = "sales";
        String reportingSchema = "reporting";
        String ordersTable = "orders";
        String reportTable = "daily";
        JdbcPostgreSqlClient client =
                new JdbcPostgreSqlClient(authoritativeConfig);
        PostgreSqlBackend backend = new PostgreSqlBackend(BACKEND_ID, client);

        try {
            try (Connection connection = adminConnection(adminConfig);
                 Statement statement = connection.createStatement()) {
                for (String role : Set.of(
                        owner, oldGrantor, runtime, reporter, obsoleteRole
                )) {
                    statement.execute(
                            "CREATE ROLE " + quoteIdentifier(role) + " NOLOGIN"
                    );
                }
                statement.execute(
                        "CREATE DATABASE " + quoteIdentifier(database)
                                + " OWNER " + quoteIdentifier(owner)
                );
            }

            try (Connection connection = databaseConnection(
                    adminConfig,
                    database,
                    adminConfig.username(),
                    adminConfig.password()
            ); Statement statement = connection.createStatement()) {
                statement.execute("SET ROLE " + quoteIdentifier(owner));
                statement.execute(
                        "CREATE SCHEMA " + quoteIdentifier(salesSchema)
                );
                statement.execute(
                        "CREATE TABLE " + quoteIdentifier(salesSchema) + "."
                                + quoteIdentifier(ordersTable)
                                + " (id integer PRIMARY KEY, note text)"
                );
                statement.execute(
                        "INSERT INTO " + quoteIdentifier(salesSchema) + "."
                                + quoteIdentifier(ordersTable)
                                + " VALUES (1, 'kept data')"
                );
                statement.execute(
                        "CREATE SCHEMA " + quoteIdentifier(reportingSchema)
                );
                statement.execute(
                        "CREATE TABLE " + quoteIdentifier(reportingSchema) + "."
                                + quoteIdentifier(reportTable) + " (day date)"
                );
                statement.execute(
                        "INSERT INTO " + quoteIdentifier(reportingSchema) + "."
                                + quoteIdentifier(reportTable)
                                + " VALUES (DATE '2026-10-01')"
                );
                statement.execute(
                        "GRANT CONNECT ON DATABASE " + quoteIdentifier(database)
                                + " TO " + quoteIdentifier(oldGrantor)
                                + " WITH GRANT OPTION"
                );
                statement.execute(
                        "GRANT USAGE ON SCHEMA " + quoteIdentifier(salesSchema)
                                + " TO " + quoteIdentifier(oldGrantor)
                                + " WITH GRANT OPTION"
                );
                statement.execute(
                        "GRANT SELECT, UPDATE ON TABLE "
                                + quoteIdentifier(salesSchema) + "."
                                + quoteIdentifier(ordersTable) + " TO "
                                + quoteIdentifier(oldGrantor)
                                + " WITH GRANT OPTION"
                );
                statement.execute(
                        "GRANT CONNECT ON DATABASE " + quoteIdentifier(database)
                                + " TO " + quoteIdentifier(reporter)
                );
                statement.execute(
                        "GRANT USAGE ON SCHEMA "
                                + quoteIdentifier(reportingSchema)
                                + " TO " + quoteIdentifier(reporter)
                );
                statement.execute(
                        "GRANT SELECT ON TABLE "
                                + quoteIdentifier(reportingSchema) + "."
                                + quoteIdentifier(reportTable) + " TO "
                                + quoteIdentifier(reporter)
                );
                statement.execute("RESET ROLE");

                statement.execute("SET ROLE " + quoteIdentifier(oldGrantor));
                statement.execute(
                        "GRANT CONNECT ON DATABASE " + quoteIdentifier(database)
                                + " TO " + quoteIdentifier(runtime)
                );
                statement.execute(
                        "GRANT USAGE ON SCHEMA " + quoteIdentifier(salesSchema)
                                + " TO " + quoteIdentifier(runtime)
                );
                statement.execute(
                        "GRANT SELECT, UPDATE ON TABLE "
                                + quoteIdentifier(salesSchema) + "."
                                + quoteIdentifier(ordersTable) + " TO "
                                + quoteIdentifier(runtime)
                );
                statement.execute(
                        "GRANT SELECT ON TABLE "
                                + quoteIdentifier(salesSchema) + "."
                                + quoteIdentifier(ordersTable) + " TO "
                                + quoteIdentifier(obsoleteRole)
                );
                statement.execute("RESET ROLE");
            }

            PactState desired = new PactState(Set.of(
                    permissionAccess(
                            runtime,
                            Map.of(
                                    "database", database,
                                    "schema", salesSchema,
                                    "table", ordersTable
                            ),
                            Map.of(
                                    "database", Set.of("CONNECT"),
                                    "schema", Set.of("USAGE"),
                                    "table", Set.of("SELECT")
                            )
                    ),
                    permissionAccess(
                            reporter,
                            Map.of(
                                    "database", database,
                                    "schema", reportingSchema,
                                    "table", reportTable
                            ),
                            Map.of(
                                    "database", Set.of("CONNECT"),
                                    "schema", Set.of("USAGE"),
                                    "table", Set.of("SELECT")
                            )
                    )
            ));
            Set<Grant> existingGrants = client.getManagedGrants(Set.of(database));
            assertTrue(hasPrivilege(
                    adminConfig,
                    database,
                    "SELECT has_table_privilege("
                            + quoteLiteral(runtime) + ", "
                            + quoteLiteral(salesSchema + "." + ordersTable)
                            + ", 'UPDATE')"
            ));

            var transaction = backend.prepare(PactState.empty(), desired);
            transaction.apply();
            Set<Grant> expectedGrants = Set.of(
                    grant(new GrantTarget(database, null, null, null),
                            runtime, Privilege.CONNECT),
                    grant(new GrantTarget(database, salesSchema, null, null),
                            runtime, Privilege.USAGE),
                    grant(new GrantTarget(database, salesSchema, ordersTable, null),
                            runtime, Privilege.SELECT),
                    grant(new GrantTarget(database, null, null, null),
                            reporter, Privilege.CONNECT),
                    grant(new GrantTarget(database, reportingSchema, null, null),
                            reporter, Privilege.USAGE),
                    grant(new GrantTarget(
                            database, reportingSchema, reportTable, null
                    ), reporter, Privilege.SELECT)
            );
            assertEquals(
                    expectedGrants,
                    client.getManagedGrants(Set.of(database))
            );
            assertTablePrivilege(
                    adminConfig, database, runtime, salesSchema, ordersTable,
                    "UPDATE", false
            );
            assertTablePrivilege(
                    adminConfig, database, obsoleteRole, salesSchema, ordersTable,
                    "SELECT", false
            );
            assertTablePrivilege(
                    adminConfig, database, reporter, reportingSchema, reportTable,
                    "SELECT", true
            );
            assertEquals(
                    owner,
                    tableOwner(adminConfig, database, salesSchema, ordersTable)
            );
            assertEquals(
                    1,
                    tableRowCount(adminConfig, database, salesSchema, ordersTable)
            );
            assertEquals(
                    1,
                    tableRowCount(
                            adminConfig, database, reportingSchema, reportTable
                    )
            );

            transaction.rollback();
            assertEquals(
                    existingGrants,
                    client.getManagedGrants(Set.of(database))
            );
            assertTablePrivilege(
                    adminConfig, database, runtime, salesSchema, ordersTable,
                    "UPDATE", true
            );
            assertTablePrivilege(
                    adminConfig, database, obsoleteRole, salesSchema, ordersTable,
                    "SELECT", true
            );
            assertEquals(
                    owner,
                    tableOwner(adminConfig, database, salesSchema, ordersTable)
            );
            assertEquals(
                    1,
                    tableRowCount(adminConfig, database, salesSchema, ordersTable)
            );
        }
        finally {
            dropDatabaseAndRole(
                    adminConfig,
                    database,
                    owner,
                    oldGrantor,
                    runtime,
                    reporter,
                    obsoleteRole
            );
        }
    }

    @Test
    void grantorModeLeavesUnmanagedLegacyAclInOtherSchemaUntouched()
            throws Exception {
        PostgreSqlConfig adminConfig = PostgreSqlConfig.from(Map.of(
                "jdbc-url", required("PACT_IT_POSTGRESQL_JDBC_URL"),
                "username", required("PACT_IT_POSTGRESQL_USERNAME"),
                "password", required("PACT_IT_POSTGRESQL_PASSWORD")
        ));
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String database = "pact_it_shared_" + suffix;
        String owner = "pact_it_shared_owner_" + suffix;
        String grantManager = "pact_it_shared_manager_" + suffix;
        String grantManagerPassword = "PactIt-" + suffix;
        String runtime = "pact_it_shared_runtime_" + suffix;
        String externalGrantor = "pact_it_shared_external_" + suffix;
        String externalRole = "pact_it_shared_reader_" + suffix;
        String managedSchema = "managed";
        String externalSchema = "other_application";
        String table = TABLE;
        PostgreSqlBackend backend;
        JdbcPostgreSqlClient client;

        try {
            try (Connection connection = adminConnection(adminConfig);
                 Statement statement = connection.createStatement()) {
                statement.execute(
                        "CREATE ROLE " + quoteIdentifier(owner) + " NOLOGIN"
                );
                statement.execute(
                        "CREATE ROLE " + quoteIdentifier(grantManager)
                                + " LOGIN PASSWORD "
                                + quoteLiteral(grantManagerPassword)
                );
                statement.execute(
                        "CREATE ROLE " + quoteIdentifier(runtime) + " NOLOGIN"
                );
                statement.execute(
                        "CREATE ROLE " + quoteIdentifier(externalGrantor)
                                + " NOLOGIN"
                );
                statement.execute(
                        "CREATE ROLE " + quoteIdentifier(externalRole) + " NOLOGIN"
                );
                statement.execute(
                        "CREATE DATABASE " + quoteIdentifier(database)
                                + " OWNER " + quoteIdentifier(owner)
                );
            }

            try (Connection connection = databaseConnection(
                    adminConfig,
                    database,
                    adminConfig.username(),
                    adminConfig.password()
            ); Statement statement = connection.createStatement()) {
                statement.execute("SET ROLE " + quoteIdentifier(owner));
                statement.execute(
                        "CREATE SCHEMA " + quoteIdentifier(managedSchema)
                );
                statement.execute(
                        "CREATE TABLE " + quoteIdentifier(managedSchema) + "."
                                + quoteIdentifier(table)
                                + " (id integer PRIMARY KEY, note text)"
                );
                statement.execute(
                        "INSERT INTO " + quoteIdentifier(managedSchema) + "."
                                + quoteIdentifier(table)
                                + " VALUES (1, 'managed data')"
                );
                statement.execute(
                        "CREATE SCHEMA " + quoteIdentifier(externalSchema)
                );
                statement.execute(
                        "CREATE TABLE " + quoteIdentifier(externalSchema) + "."
                                + quoteIdentifier(table)
                                + " (id integer PRIMARY KEY, note text)"
                );
                statement.execute(
                        "INSERT INTO " + quoteIdentifier(externalSchema) + "."
                                + quoteIdentifier(table)
                                + " VALUES (1, 'external data')"
                );
                statement.execute(
                        "GRANT CONNECT ON DATABASE " + quoteIdentifier(database)
                                + " TO " + quoteIdentifier(grantManager)
                                + " WITH GRANT OPTION"
                );
                statement.execute(
                        "GRANT USAGE ON SCHEMA " + quoteIdentifier(managedSchema)
                                + " TO " + quoteIdentifier(grantManager)
                                + " WITH GRANT OPTION"
                );
                statement.execute(
                        "GRANT SELECT, UPDATE ON TABLE "
                                + quoteIdentifier(managedSchema) + "."
                                + quoteIdentifier(table) + " TO "
                                + quoteIdentifier(grantManager)
                                + " WITH GRANT OPTION"
                );
                statement.execute(
                        "GRANT CONNECT ON DATABASE " + quoteIdentifier(database)
                                + " TO " + quoteIdentifier(externalGrantor)
                                + " WITH GRANT OPTION"
                );
                statement.execute(
                        "GRANT USAGE ON SCHEMA "
                                + quoteIdentifier(externalSchema) + " TO "
                                + quoteIdentifier(externalGrantor)
                                + " WITH GRANT OPTION"
                );
                statement.execute(
                        "GRANT SELECT, UPDATE ON TABLE "
                                + quoteIdentifier(externalSchema) + "."
                                + quoteIdentifier(table) + " TO "
                                + quoteIdentifier(externalGrantor)
                                + " WITH GRANT OPTION"
                );
                statement.execute("RESET ROLE");

                statement.execute(
                        "SET ROLE " + quoteIdentifier(grantManager)
                );
                statement.execute(
                        "GRANT CONNECT ON DATABASE " + quoteIdentifier(database)
                                + " TO " + quoteIdentifier(runtime)
                );
                statement.execute(
                        "GRANT USAGE ON SCHEMA " + quoteIdentifier(managedSchema)
                                + " TO " + quoteIdentifier(runtime)
                );
                statement.execute(
                        "GRANT UPDATE ON TABLE "
                                + quoteIdentifier(managedSchema) + "."
                                + quoteIdentifier(table) + " TO "
                                + quoteIdentifier(runtime)
                );
                statement.execute("RESET ROLE");

                statement.execute(
                        "SET ROLE " + quoteIdentifier(externalGrantor)
                );
                statement.execute(
                        "GRANT CONNECT ON DATABASE " + quoteIdentifier(database)
                                + " TO " + quoteIdentifier(externalRole)
                );
                statement.execute(
                        "GRANT USAGE ON SCHEMA "
                                + quoteIdentifier(externalSchema) + " TO "
                                + quoteIdentifier(externalRole)
                );
                statement.execute(
                        "GRANT SELECT ON TABLE "
                                + quoteIdentifier(externalSchema) + "."
                                + quoteIdentifier(table) + " TO "
                                + quoteIdentifier(externalRole)
                );
                statement.execute("RESET ROLE");
            }

            PostgreSqlConfig grantManagerConfig = PostgreSqlConfig.from(
                    Map.of(
                            "jdbc-url", adminConfig.jdbcUrl(),
                            "username", grantManager,
                            "password", grantManagerPassword
                    )
            );
            client = new JdbcPostgreSqlClient(grantManagerConfig);
            backend = new PostgreSqlBackend(BACKEND_ID, client);
            PactState desired = grantsOnlyState(
                    database, managedSchema, runtime
            );

            assertTablePrivilege(
                    adminConfig, database, runtime, managedSchema, table,
                    "UPDATE", true
            );
            assertTablePrivilege(
                    adminConfig, database, externalRole, externalSchema, table,
                    "SELECT", true
            );
            backend.prepare(PactState.empty(), desired).apply();

            assertEquals(
                    Set.of(
                            grant(
                                    new GrantTarget(database, null, null, null),
                                    runtime,
                                    Privilege.CONNECT
                            ),
                            grant(
                                    new GrantTarget(
                                            database, managedSchema, null, null
                                    ),
                                    runtime,
                                    Privilege.USAGE
                            ),
                            grant(
                                    new GrantTarget(
                                            database, managedSchema, table, null
                                    ),
                                    runtime,
                                    Privilege.SELECT
                            )
                    ),
                    client.getManagedGrants(Set.of(database))
            );
            assertTablePrivilege(
                    adminConfig, database, runtime, managedSchema, table,
                    "SELECT", true
            );
            assertTablePrivilege(
                    adminConfig, database, runtime, managedSchema, table,
                    "UPDATE", false
            );
            assertTablePrivilege(
                    adminConfig, database, externalRole, externalSchema, table,
                    "SELECT", true
            );
            assertTablePrivilege(
                    adminConfig, database, externalRole, externalSchema, table,
                    "UPDATE", false
            );
            assertEquals(
                    owner,
                    tableOwner(adminConfig, database, managedSchema, table)
            );
            assertEquals(
                    owner,
                    tableOwner(adminConfig, database, externalSchema, table)
            );
            assertEquals(
                    1,
                    tableRowCount(adminConfig, database, managedSchema, table)
            );
            assertEquals(
                    1,
                    tableRowCount(adminConfig, database, externalSchema, table)
            );

            backend.prepare(desired, PactState.empty()).apply();
            assertTablePrivilege(
                    adminConfig, database, runtime, managedSchema, table,
                    "SELECT", false
            );
            assertTablePrivilege(
                    adminConfig, database, externalRole, externalSchema, table,
                    "SELECT", true
            );
            assertEquals(
                    1,
                    tableRowCount(adminConfig, database, externalSchema, table)
            );
        }
        finally {
            dropDatabaseAndRole(
                    adminConfig,
                    database,
                    owner,
                    grantManager,
                    runtime,
                    externalGrantor,
                    externalRole
            );
        }
    }

    @Test
    void authoritativeModeAcceptsColumnPrivilegeForTableOwner()
            throws Exception {
        PostgreSqlConfig adminConfig = PostgreSqlConfig.from(Map.of(
                "jdbc-url", required("PACT_IT_POSTGRESQL_JDBC_URL"),
                "username", required("PACT_IT_POSTGRESQL_USERNAME"),
                "password", required("PACT_IT_POSTGRESQL_PASSWORD")
        ));
        PostgreSqlConfig authoritativeConfig = PostgreSqlConfig.from(Map.of(
                "jdbc-url", adminConfig.jdbcUrl(),
                "username", adminConfig.username(),
                "password", adminConfig.password(),
                "reconciliation-mode", "authoritative"
        ));
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String database = "pact_it_owner_column_" + suffix;
        String owner = "pact_it_column_owner_" + suffix;
        String table = "foo";
        String column = "name";
        JdbcPostgreSqlClient client =
                new JdbcPostgreSqlClient(authoritativeConfig);
        PostgreSqlBackend backend = new PostgreSqlBackend(BACKEND_ID, client);

        try {
            createDatabase(adminConfig, database);
            try (Connection connection = databaseConnection(
                    adminConfig,
                    database,
                    adminConfig.username(),
                    adminConfig.password()
            ); Statement statement = connection.createStatement()) {
                statement.execute(
                        "CREATE ROLE " + quoteIdentifier(owner) + " NOLOGIN"
                );
                statement.execute(
                        "CREATE TABLE public." + quoteIdentifier(table)
                                + " (" + quoteIdentifier(column) + " text)"
                );
                statement.execute(
                        "ALTER TABLE public." + quoteIdentifier(table)
                                + " OWNER TO " + quoteIdentifier(owner)
                );
            }

            PactState desired = new PactState(Set.of(permissionAccess(
                    owner,
                    Map.of(
                            "database", database,
                            "schema", "public",
                            "table", table,
                            "column", column
                    ),
                    Map.of("column", Set.of("SELECT"))
            )));
            backend.prepare(PactState.empty(), desired).apply();

            assertTrue(hasPrivilege(
                    adminConfig,
                    database,
                    "SELECT has_column_privilege("
                            + quoteLiteral(owner) + ", "
                            + quoteLiteral("public." + table) + ", "
                            + quoteLiteral(column) + ", 'SELECT')"
            ));

            backend.prepare(desired, PactState.empty()).apply();
        }
        finally {
            dropDatabaseAndRole(adminConfig, database, owner);
        }
    }

    @Test
    void authoritativeModeAdoptsAndRollsBackExistingDefaultPrivileges()
            throws Exception {
        PostgreSqlConfig adminConfig = PostgreSqlConfig.from(Map.of(
                "jdbc-url", required("PACT_IT_POSTGRESQL_JDBC_URL"),
                "username", required("PACT_IT_POSTGRESQL_USERNAME"),
                "password", required("PACT_IT_POSTGRESQL_PASSWORD")
        ));
        PostgreSqlConfig authoritativeConfig = PostgreSqlConfig.from(Map.of(
                "jdbc-url", adminConfig.jdbcUrl(),
                "username", adminConfig.username(),
                "password", adminConfig.password(),
                "reconciliation-mode", "authoritative"
        ));
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String database = "pact_it_default_adopt_" + suffix;
        String creator = "pact_it_default_creator_" + suffix;
        String runtime = "pact_it_default_runtime_" + suffix;
        String legacyRole = "pact_it_default_legacy_" + suffix;
        String schema = "managed";
        String table = "exception_table";
        JdbcPostgreSqlClient client =
                new JdbcPostgreSqlClient(authoritativeConfig);
        PostgreSqlBackend backend = new PostgreSqlBackend(BACKEND_ID, client);

        try {
            createDatabase(adminConfig, database);
            try (Connection connection = databaseConnection(
                    adminConfig,
                    database,
                    adminConfig.username(),
                    adminConfig.password()
            ); Statement statement = connection.createStatement()) {
                statement.execute(
                        "CREATE ROLE " + quoteIdentifier(creator) + " NOLOGIN"
                );
                statement.execute(
                        "CREATE ROLE " + quoteIdentifier(runtime) + " NOLOGIN"
                );
                statement.execute(
                        "CREATE ROLE " + quoteIdentifier(legacyRole) + " NOLOGIN"
                );
                statement.execute(
                        "CREATE SCHEMA " + quoteIdentifier(schema)
                                + " AUTHORIZATION " + quoteIdentifier(creator)
                );
                statement.execute("SET ROLE " + quoteIdentifier(creator));
                statement.execute(
                        "CREATE TABLE " + quoteIdentifier(schema) + "."
                                + quoteIdentifier(table) + " (id integer)"
                );
                statement.execute(
                        "ALTER DEFAULT PRIVILEGES IN SCHEMA "
                                + quoteIdentifier(schema)
                                + " GRANT SELECT, UPDATE ON TABLES TO "
                                + quoteIdentifier(runtime)
                );
                statement.execute(
                        "ALTER DEFAULT PRIVILEGES IN SCHEMA "
                                + quoteIdentifier(schema)
                                + " GRANT USAGE ON SEQUENCES TO "
                                + quoteIdentifier(runtime)
                );
                statement.execute(
                        "ALTER DEFAULT PRIVILEGES IN SCHEMA "
                                + quoteIdentifier(schema)
                                + " GRANT EXECUTE ON FUNCTIONS TO "
                                + quoteIdentifier(runtime)
                );
                statement.execute(
                        "ALTER DEFAULT PRIVILEGES IN SCHEMA "
                                + quoteIdentifier(schema)
                                + " GRANT DELETE ON TABLES TO "
                                + quoteIdentifier(legacyRole)
                );
                statement.execute("RESET ROLE");
            }

            DefaultPrivilegeScope tableScope = new DefaultPrivilegeScope(
                    database, schema, creator, DefaultPrivilegeType.TABLES
            );
            DefaultPrivilegeScope sequenceScope = new DefaultPrivilegeScope(
                    database, schema, creator, DefaultPrivilegeType.SEQUENCES
            );
            DefaultPrivilegeScope routineScope = new DefaultPrivilegeScope(
                    database, schema, creator, DefaultPrivilegeType.ROUTINES
            );
            Set<DefaultPrivilegeScope> scopes = Set.of(
                    tableScope, sequenceScope, routineScope
            );
            Set<DefaultPrivilegeGrant> existingDefaults =
                    client.getManagedDefaultPrivileges(scopes);
            assertTrue(existingDefaults.contains(new DefaultPrivilegeGrant(
                    tableScope, legacyRole, Privilege.DELETE
            )));
            PactState desired = defaultPrivilegesState(
                    database, schema, creator, runtime
            );
            Set<DefaultPrivilegeGrant> desiredDefaults = Set.of(
                    new DefaultPrivilegeGrant(
                            tableScope, runtime, Privilege.SELECT
                    ),
                    new DefaultPrivilegeGrant(
                            tableScope, runtime, Privilege.UPDATE
                    ),
                    new DefaultPrivilegeGrant(
                            sequenceScope, runtime, Privilege.USAGE
                    ),
                    new DefaultPrivilegeGrant(
                            routineScope, runtime, Privilege.EXECUTE
                    )
            );
            var transaction = backend.prepare(PactState.empty(), desired);
            transaction.apply();

            assertEquals(desiredDefaults, client.getManagedDefaultPrivileges(scopes));
            assertTrue(hasPrivilege(
                    adminConfig,
                    database,
                    "SELECT has_table_privilege("
                            + quoteLiteral(runtime) + ", "
                            + quoteLiteral(schema + "." + table)
                            + ", 'SELECT')"
            ));
            assertFalse(hasPrivilege(
                    adminConfig,
                    database,
                    "SELECT has_table_privilege("
                            + quoteLiteral(legacyRole) + ", "
                            + quoteLiteral(schema + "." + table)
                            + ", 'DELETE')"
            ));

            try (Connection connection = databaseConnection(
                    adminConfig,
                    database,
                    adminConfig.username(),
                    adminConfig.password()
            ); Statement statement = connection.createStatement()) {
                statement.execute("SET ROLE " + quoteIdentifier(creator));
                statement.execute(
                        "CREATE TABLE " + quoteIdentifier(schema)
                                + ".future_table (id integer)"
                );
                statement.execute(
                        "CREATE SEQUENCE " + quoteIdentifier(schema)
                                + ".future_sequence"
                );
                statement.execute(
                        "CREATE FUNCTION " + quoteIdentifier(schema)
                                + ".future_function() RETURNS integer "
                                + "LANGUAGE SQL IMMUTABLE AS 'SELECT 1'"
                );
                statement.execute("RESET ROLE");
            }
            assertTrue(hasPrivilege(
                    adminConfig,
                    database,
                    "SELECT has_table_privilege("
                            + quoteLiteral(runtime) + ", "
                            + quoteLiteral(schema + ".future_table")
                            + ", 'UPDATE')"
            ));
            assertFalse(hasPrivilege(
                    adminConfig,
                    database,
                    "SELECT has_table_privilege("
                            + quoteLiteral(legacyRole) + ", "
                            + quoteLiteral(schema + ".future_table")
                            + ", 'DELETE')"
            ));
            assertTrue(hasPrivilege(
                    adminConfig,
                    database,
                    "SELECT has_sequence_privilege("
                            + quoteLiteral(runtime) + ", "
                            + quoteLiteral(schema + ".future_sequence")
                            + ", 'USAGE')"
            ));
            assertTrue(hasPrivilege(
                    adminConfig,
                    database,
                    "SELECT has_function_privilege("
                            + quoteLiteral(runtime) + ", "
                            + quoteLiteral(schema + ".future_function()")
                            + ", 'EXECUTE')"
            ));

            transaction.rollback();
            assertEquals(existingDefaults, client.getManagedDefaultPrivileges(scopes));
        }
        finally {
            dropDatabaseAndRole(
                    adminConfig, database, creator, runtime, legacyRole
            );
        }
    }

    @Test
    void defaultPrivilegesCoverExistingCreatorOwnedAndFutureObjects()
                throws Exception {
            PostgreSqlConfig adminConfig = PostgreSqlConfig.from(Map.of(
                    "jdbc-url", required("PACT_IT_POSTGRESQL_JDBC_URL"),
                    "username", required("PACT_IT_POSTGRESQL_USERNAME"),
                    "password", required("PACT_IT_POSTGRESQL_PASSWORD")
            ));
            PostgreSqlConfig authoritativeConfig = PostgreSqlConfig.from(Map.of(
                    "jdbc-url", adminConfig.jdbcUrl(),
                    "username", adminConfig.username(),
                    "password", adminConfig.password(),
                    "reconciliation-mode", "authoritative"
            ));
            String suffix = UUID.randomUUID().toString().replace("-", "");
            String database = "pact_it_defaults_" + suffix;
            String creator = "pact_it_creator_" + suffix;
            String otherCreator = "pact_it_other_creator_" + suffix;
            String runtime = "pact_it_runtime_" + suffix;
            String schema = "managed";
            JdbcPostgreSqlClient client =
                    new JdbcPostgreSqlClient(authoritativeConfig);
            PostgreSqlBackend backend = new PostgreSqlBackend(BACKEND_ID, client);

            try {
                createDatabase(adminConfig, database);
                try (Connection connection = databaseConnection(
                        adminConfig,
                        database,
                        adminConfig.username(),
                        adminConfig.password()
                ); Statement statement = connection.createStatement()) {
                    statement.execute(
                            "CREATE ROLE " + quoteIdentifier(creator) + " NOLOGIN"
                    );
                    statement.execute(
                            "CREATE ROLE " + quoteIdentifier(otherCreator) + " NOLOGIN"
                    );
                    statement.execute(
                            "CREATE ROLE " + quoteIdentifier(runtime) + " NOLOGIN"
                    );
                    statement.execute(
                            "CREATE SCHEMA " + quoteIdentifier(schema)
                                    + " AUTHORIZATION " + quoteIdentifier(creator)
                    );
                    statement.execute(
                            "GRANT CREATE ON SCHEMA " + quoteIdentifier(schema)
                                    + " TO " + quoteIdentifier(otherCreator)
                    );
                    statement.execute("SET ROLE " + quoteIdentifier(creator));
                    statement.execute(
                            "CREATE TABLE " + quoteIdentifier(schema)
                                    + ".owned_table (id integer)"
                    );
                    statement.execute(
                            "CREATE TABLE " + quoteIdentifier(schema)
                                    + ".exception_table (id integer)"
                    );
                    statement.execute(
                            "CREATE SEQUENCE " + quoteIdentifier(schema)
                                    + ".owned_sequence"
                    );
                    statement.execute(
                            "CREATE FUNCTION " + quoteIdentifier(schema)
                                    + ".owned_function() RETURNS integer "
                                    + "LANGUAGE SQL IMMUTABLE AS 'SELECT 1'"
                    );
                    statement.execute("RESET ROLE");
                    statement.execute("SET ROLE " + quoteIdentifier(otherCreator));
                    statement.execute(
                            "CREATE TABLE " + quoteIdentifier(schema)
                                    + ".other_owner_table (id integer)"
                    );
                    statement.execute("RESET ROLE");
                }

                PactState desired = defaultPrivilegesState(
                        database, schema, creator, runtime
                );
                backend.prepare(PactState.empty(), desired).apply();

                DefaultPrivilegeScope tableDefaults = new DefaultPrivilegeScope(
                        database, schema, creator, DefaultPrivilegeType.TABLES
                );
                DefaultPrivilegeScope sequenceDefaults =
                        new DefaultPrivilegeScope(
                                database, schema, creator,
                                DefaultPrivilegeType.SEQUENCES
                        );
                DefaultPrivilegeScope routineDefaults = new DefaultPrivilegeScope(
                        database, schema, creator, DefaultPrivilegeType.ROUTINES
                );
                assertEquals(
                        Set.of(
                                new DefaultPrivilegeGrant(
                                        tableDefaults, runtime, Privilege.SELECT
                                ),
                                new DefaultPrivilegeGrant(
                                        tableDefaults, runtime, Privilege.UPDATE
                                ),
                                new DefaultPrivilegeGrant(
                                        sequenceDefaults, runtime, Privilege.USAGE
                                ),
                                new DefaultPrivilegeGrant(
                                        routineDefaults, runtime, Privilege.EXECUTE
                                )
                        ),
                        client.getManagedDefaultPrivileges(Set.of(
                                tableDefaults, sequenceDefaults, routineDefaults
                        ))
                );
                assertTrue(hasPrivilege(
                        adminConfig, database,
                        "SELECT has_table_privilege("
                                + quoteLiteral(runtime) + ", "
                                + quoteLiteral(schema + ".owned_table")
                                + ", 'SELECT')"
                ));
                assertFalse(hasPrivilege(
                        adminConfig, database,
                        "SELECT has_table_privilege("
                                + quoteLiteral(runtime) + ", "
                                + quoteLiteral(schema + ".owned_table")
                                + ", 'DELETE')"
                ));
                assertTrue(hasPrivilege(
                        adminConfig, database,
                        "SELECT has_table_privilege("
                                + quoteLiteral(runtime) + ", "
                                + quoteLiteral(schema + ".exception_table")
                                + ", 'SELECT')"
                ));
                assertFalse(hasPrivilege(
                        adminConfig, database,
                        "SELECT has_table_privilege("
                                + quoteLiteral(runtime) + ", "
                                + quoteLiteral(schema + ".exception_table")
                                + ", 'UPDATE')"
                ));
                assertFalse(hasPrivilege(
                        adminConfig, database,
                        "SELECT has_table_privilege("
                                + quoteLiteral(runtime) + ", "
                                + quoteLiteral(schema + ".other_owner_table")
                                + ", 'SELECT')"
                ));

                try (Connection connection = databaseConnection(
                        adminConfig,
                        database,
                        adminConfig.username(),
                        adminConfig.password()
                ); Statement statement = connection.createStatement()) {
                    statement.execute("SET ROLE " + quoteIdentifier(creator));
                    statement.execute(
                            "CREATE TABLE " + quoteIdentifier(schema)
                                    + ".future_table (id integer)"
                    );
                    statement.execute(
                            "CREATE SEQUENCE " + quoteIdentifier(schema)
                                    + ".future_sequence"
                    );
                    statement.execute(
                            "CREATE FUNCTION " + quoteIdentifier(schema)
                                    + ".future_function() RETURNS integer "
                                    + "LANGUAGE SQL IMMUTABLE AS 'SELECT 1'"
                    );
                    statement.execute("RESET ROLE");
                    statement.execute(
                            "GRANT CREATE ON SCHEMA " + quoteIdentifier(schema)
                                    + " TO " + quoteIdentifier(otherCreator)
                    );
                    statement.execute("SET ROLE " + quoteIdentifier(otherCreator));
                    statement.execute(
                            "CREATE TABLE " + quoteIdentifier(schema)
                                    + ".future_other_owner_table (id integer)"
                    );
                    statement.execute("RESET ROLE");
                }
                assertTrue(hasPrivilege(
                        adminConfig, database,
                        "SELECT has_table_privilege("
                                + quoteLiteral(runtime) + ", "
                                + quoteLiteral(schema + ".future_table")
                                + ", 'SELECT')"
                ));
                assertTrue(hasPrivilege(
                        adminConfig, database,
                        "SELECT has_sequence_privilege("
                                + quoteLiteral(runtime) + ", "
                                + quoteLiteral(schema + ".future_sequence")
                                + ", 'USAGE')"
                ));
                assertTrue(hasPrivilege(
                        adminConfig, database,
                        "SELECT has_function_privilege("
                                + quoteLiteral(runtime) + ", "
                                + quoteLiteral(schema + ".future_function()")
                                + ", 'EXECUTE')"
                ));
                assertFalse(hasPrivilege(
                        adminConfig, database,
                        "SELECT has_table_privilege("
                                + quoteLiteral(runtime) + ", "
                                + quoteLiteral(schema + ".future_other_owner_table")
                                + ", 'SELECT')"
                ));

                backend.prepare(desired, PactState.empty()).apply();
                assertTrue(client.getManagedDefaultPrivileges(Set.of(
                        tableDefaults, sequenceDefaults, routineDefaults
                )).isEmpty());
            }
            finally {
                dropDatabaseAndRole(
                        adminConfig, database, creator, otherCreator, runtime
                );
            }
    }

    @Test
    void reconcilesApplicationDeveloperAndAnalystAccess() throws Exception {
            PostgreSqlConfig adminConfig = PostgreSqlConfig.from(Map.of(
                    "jdbc-url", required("PACT_IT_POSTGRESQL_JDBC_URL"),
                    "username", required("PACT_IT_POSTGRESQL_USERNAME"),
                    "password", required("PACT_IT_POSTGRESQL_PASSWORD")
            ));
            PostgreSqlConfig authoritativeConfig = PostgreSqlConfig.from(Map.of(
                    "jdbc-url", adminConfig.jdbcUrl(),
                    "username", adminConfig.username(),
                    "password", adminConfig.password(),
                    "reconciliation-mode", "authoritative"
            ));
            String suffix = UUID.randomUUID().toString().replace("-", "");
            String database = "pact_it_app_" + suffix;
            String app = "pact_it_app_role_" + suffix;
            String developer = "pact_it_developer_" + suffix;
            String analystOne = "pact_it_analyst_one_" + suffix;
            String analystTwo = "pact_it_analyst_two_" + suffix;
            String appSchema = "app_data";
            String analystSchema = "analyst_scratch";
            JdbcPostgreSqlClient client =
                    new JdbcPostgreSqlClient(authoritativeConfig);
            PostgreSqlBackend backend = new PostgreSqlBackend(BACKEND_ID, client);

            try {
                createDatabase(adminConfig, database);
                try (Connection connection = databaseConnection(
                        adminConfig,
                        database,
                        adminConfig.username(),
                        adminConfig.password()
                ); Statement statement = connection.createStatement()) {
                    for (String role : Set.of(
                            app, developer, analystOne, analystTwo
                    )) {
                        statement.execute(
                                "CREATE ROLE " + quoteIdentifier(role) + " NOLOGIN"
                        );
                    }
                }

                PactState bootstrap = new PactState(Set.of(
                        permissionAccess(
                                app,
                                Map.of("database", database),
                                Map.of("database", Set.of("CONNECT", "CREATE"))
                        ),
                        permissionAccess(
                                developer,
                                Map.of("database", database),
                                Map.of("database", Set.of("CONNECT"))
                        ),
                        permissionAccess(
                                analystOne,
                                Map.of("database", database),
                                Map.of("database", Set.of("CONNECT", "CREATE"))
                        ),
                        permissionAccess(
                                analystTwo,
                                Map.of("database", database),
                                Map.of("database", Set.of("CONNECT", "CREATE"))
                        )
                ));
                backend.prepare(PactState.empty(), bootstrap).apply();

                try (Connection connection = databaseConnection(
                        adminConfig,
                        database,
                        adminConfig.username(),
                        adminConfig.password()
                ); Statement statement = connection.createStatement()) {
                    statement.execute("SET ROLE " + quoteIdentifier(app));
                    statement.execute(
                            "CREATE SCHEMA " + quoteIdentifier(appSchema)
                    );
                    statement.execute(
                            "CREATE TABLE " + quoteIdentifier(appSchema)
                                    + ".records (id integer PRIMARY KEY, payload text)"
                    );
                    statement.execute(
                            "CREATE SEQUENCE " + quoteIdentifier(appSchema)
                                    + ".existing_sequence"
                    );
                    statement.execute(
                            "INSERT INTO " + quoteIdentifier(appSchema)
                                    + ".records VALUES (1, 'initial')"
                    );
                    statement.execute("RESET ROLE");
                }

                PactState desired = new PactState(Set.of(
                        permissionAccess(
                                app,
                                Map.of("database", database),
                                Map.of("database", Set.of("CONNECT", "CREATE"))
                        ),
                        permissionAccess(
                                developer,
                                Map.of("database", database),
                                Map.of("database", Set.of("CONNECT"))
                        ),
                        permissionAccess(
                                analystOne,
                                Map.of("database", database),
                                Map.of("database", Set.of("CONNECT", "CREATE"))
                        ),
                        permissionAccess(
                                analystTwo,
                                Map.of("database", database),
                                Map.of("database", Set.of("CONNECT", "CREATE"))
                        ),
                        permissionAccessWithDefaults(
                                developer,
                                database,
                                appSchema,
                                app,
                                Map.of("schema", Set.of("USAGE")),
                                Map.of(
                                        "table", Set.of(
                                                "SELECT", "INSERT", "UPDATE", "DELETE"
                                        ),
                                        "sequence", Set.of(
                                                "USAGE", "SELECT", "UPDATE"
                                        )
                                )
                        ),
                        permissionAccessWithDefaults(
                                analystOne,
                                database,
                                appSchema,
                                app,
                                Map.of("schema", Set.of("USAGE")),
                                Map.of(
                                        "table", Set.of("SELECT"),
                                        "sequence", Set.of("SELECT")
                                )
                        ),
                        permissionAccessWithDefaults(
                                analystTwo,
                                database,
                                appSchema,
                                app,
                                Map.of("schema", Set.of("USAGE")),
                                Map.of(
                                        "table", Set.of("SELECT"),
                                        "sequence", Set.of("SELECT")
                                )
                        )
                ));
                backend.prepare(bootstrap, desired).apply();

                assertTablePrivilege(
                        adminConfig, database, developer, appSchema, "records",
                        "SELECT", true
                );
                assertTablePrivilege(
                        adminConfig, database, developer, appSchema, "records",
                        "INSERT", true
                );
                assertTablePrivilege(
                        adminConfig, database, developer, appSchema, "records",
                        "UPDATE", true
                );
                assertTablePrivilege(
                        adminConfig, database, developer, appSchema, "records",
                        "DELETE", true
                );
                assertSequencePrivilege(
                        adminConfig, database, developer, appSchema,
                        "existing_sequence", "USAGE", true
                );
                for (String analyst : Set.of(analystOne, analystTwo)) {
                    assertTablePrivilege(
                            adminConfig, database, analyst, appSchema, "records",
                            "SELECT", true
                    );
                    assertTablePrivilege(
                            adminConfig, database, analyst, appSchema, "records",
                            "INSERT", false
                    );
                    assertTablePrivilege(
                            adminConfig, database, analyst, appSchema, "records",
                            "UPDATE", false
                    );
                    assertTablePrivilege(
                            adminConfig, database, analyst, appSchema, "records",
                            "DELETE", false
                    );
                    assertSequencePrivilege(
                            adminConfig, database, analyst, appSchema,
                            "existing_sequence", "SELECT", true
                    );
                    assertSequencePrivilege(
                            adminConfig, database, analyst, appSchema,
                            "existing_sequence", "USAGE", false
                    );
                }
                assertFalse(hasPrivilege(
                        adminConfig, database,
                        "SELECT has_database_privilege("
                                + quoteLiteral(developer) + ", "
                                + quoteLiteral(database) + ", 'CREATE')"
                ));

                try (Connection connection = databaseConnection(
                        adminConfig,
                        database,
                        adminConfig.username(),
                        adminConfig.password()
                ); Statement statement = connection.createStatement()) {
                    statement.execute("SET ROLE " + quoteIdentifier(developer));
                    statement.execute(
                            "INSERT INTO " + quoteIdentifier(appSchema)
                                    + ".records VALUES (2, 'developer write')"
                    );
                    statement.execute(
                            "UPDATE " + quoteIdentifier(appSchema)
                                    + ".records SET payload = 'updated' WHERE id = 2"
                    );
                    statement.execute("RESET ROLE");

                    statement.execute("SET ROLE " + quoteIdentifier(analystOne));
                    try (var rows = statement.executeQuery(
                            "SELECT count(*) FROM " + quoteIdentifier(appSchema)
                                    + ".records"
                    )) {
                        assertTrue(rows.next());
                        assertEquals(2, rows.getInt(1));
                    }
                    statement.execute("RESET ROLE");
                }

                try (Connection connection = databaseConnection(
                        adminConfig,
                        database,
                        adminConfig.username(),
                        adminConfig.password()
                ); Statement statement = connection.createStatement()) {
                    statement.execute("SET ROLE " + quoteIdentifier(app));
                    statement.execute(
                            "CREATE TABLE " + quoteIdentifier(appSchema)
                                    + ".future_records (id integer, payload text)"
                    );
                    statement.execute(
                            "CREATE SEQUENCE " + quoteIdentifier(appSchema)
                                    + ".future_sequence"
                    );
                    statement.execute("RESET ROLE");
                }
                for (String analyst : Set.of(analystOne, analystTwo)) {
                    assertTablePrivilege(
                            adminConfig, database, analyst, appSchema,
                            "future_records", "SELECT", true
                    );
                    assertTablePrivilege(
                            adminConfig, database, analyst, appSchema,
                            "future_records", "UPDATE", false
                    );
                    assertSequencePrivilege(
                            adminConfig, database, analyst, appSchema,
                            "future_sequence", "SELECT", true
                    );
                }
                assertSequencePrivilege(
                        adminConfig, database, developer, appSchema,
                        "future_sequence", "USAGE", true
                );

                try (Connection connection = databaseConnection(
                        adminConfig,
                        database,
                        adminConfig.username(),
                        adminConfig.password()
                ); Statement statement = connection.createStatement()) {
                    statement.execute(
                            "SET ROLE " + quoteIdentifier(analystOne)
                    );
                    statement.execute(
                            "CREATE SCHEMA " + quoteIdentifier(analystSchema)
                    );
                    statement.execute(
                            "CREATE TABLE " + quoteIdentifier(analystSchema)
                                    + ".scratch (id integer)"
                    );
                    statement.execute(
                            "INSERT INTO " + quoteIdentifier(analystSchema)
                                    + ".scratch VALUES (1)"
                    );
                    statement.execute(
                            "ALTER TABLE " + quoteIdentifier(analystSchema)
                                    + ".scratch ADD COLUMN note text"
                    );
                    statement.execute(
                            "DROP TABLE " + quoteIdentifier(analystSchema)
                                    + ".scratch"
                    );
                    statement.execute("RESET ROLE");
                }
                assertFalse(hasPrivilege(
                        adminConfig, database,
                        "SELECT has_schema_privilege("
                                + quoteLiteral(analystTwo) + ", "
                                + quoteLiteral(analystSchema) + ", 'USAGE')"
                ));
                assertFalse(hasPrivilege(
                        adminConfig, database,
                        "SELECT has_database_privilege("
                                + quoteLiteral(developer) + ", "
                                + quoteLiteral(database) + ", 'CREATE')"
                ));

                backend.prepare(desired, PactState.empty()).apply();
            }
            finally {
                dropDatabaseAndRole(
                        adminConfig,
                        database,
                        app,
                        developer,
                        analystOne,
                        analystTwo
                );
            }
    }

    private static void createAuthoritativeFixture(
            PostgreSqlConfig config,
            String database,
            String owner,
            String oldGrantor,
            String oldGrantorPassword,
            String appRole,
            String appPassword,
            String schema
    ) throws SQLException {
        try (Connection connection = adminConnection(config);
             Statement statement = connection.createStatement()) {
            statement.execute(
                    "CREATE ROLE " + quoteIdentifier(owner) + " NOLOGIN"
            );
            statement.execute(
                    "CREATE ROLE " + quoteIdentifier(oldGrantor)
                            + " LOGIN PASSWORD "
                            + quoteLiteral(oldGrantorPassword)
            );
            statement.execute(
                    "CREATE ROLE " + quoteIdentifier(appRole)
                            + " LOGIN PASSWORD " + quoteLiteral(appPassword)
            );
            statement.execute(
                    "CREATE DATABASE " + quoteIdentifier(database)
                            + " OWNER " + quoteIdentifier(owner)
            );
        }

        try (Connection connection = databaseConnection(
                config,
                database,
                config.username(),
                config.password()
        ); Statement statement = connection.createStatement()) {
            statement.execute("SET ROLE " + quoteIdentifier(owner));
            statement.execute("CREATE SCHEMA " + quoteIdentifier(schema));
            statement.execute(
                    "CREATE TABLE " + quoteIdentifier(schema) + "."
                            + quoteIdentifier(TABLE) + " (id integer)"
            );
            statement.execute(
                    "CREATE FUNCTION " + quoteIdentifier(schema)
                            + ".increment(integer) RETURNS integer "
                            + "LANGUAGE SQL IMMUTABLE AS 'SELECT $1 + 1'"
            );
            statement.execute(
                    "GRANT CONNECT ON DATABASE " + quoteIdentifier(database)
                            + " TO " + quoteIdentifier(oldGrantor)
                            + " WITH GRANT OPTION"
            );
            statement.execute(
                    "GRANT USAGE ON SCHEMA " + quoteIdentifier(schema)
                            + " TO " + quoteIdentifier(oldGrantor)
                            + " WITH GRANT OPTION"
            );
            statement.execute(
                    "GRANT SELECT, UPDATE ON TABLE "
                            + quoteIdentifier(schema) + "."
                            + quoteIdentifier(TABLE) + " TO "
                            + quoteIdentifier(oldGrantor)
                            + " WITH GRANT OPTION"
            );
            statement.execute(
                    "GRANT SELECT ON TABLE " + quoteIdentifier(schema) + "."
                            + quoteIdentifier(TABLE) + " TO "
                            + quoteIdentifier(appRole)
                            + " WITH GRANT OPTION"
            );
            statement.execute("RESET ROLE");
            statement.execute("SET ROLE " + quoteIdentifier(oldGrantor));
            statement.execute(
                    "GRANT CONNECT ON DATABASE " + quoteIdentifier(database)
                            + " TO " + quoteIdentifier(appRole)
            );
            statement.execute(
                    "GRANT USAGE ON SCHEMA " + quoteIdentifier(schema)
                            + " TO " + quoteIdentifier(appRole)
            );
            statement.execute(
                    "GRANT SELECT, UPDATE ON TABLE "
                            + quoteIdentifier(schema) + "."
                            + quoteIdentifier(TABLE) + " TO "
                            + quoteIdentifier(appRole)
            );
        }
    }

    private static PactState authoritativeState(
            String database,
            String schema,
            String appRole
    ) {
        PactState appState = grantsOnlyState(database, schema, appRole);
        Access publicAccess = new Access(
                "PUBLIC",
                new Resource(
                        BACKEND_ID,
                        Map.of(
                                "database", database,
                                "schema", schema,
                                "table", TABLE
                        )
                ),
                Map.of(
                        "permissions",
                        Value.object(
                                Map.of(
                                        "schema",
                                        Value.set(Set.of(
                                                Value.string("USAGE")
                                        )),
                                        "table",
                                        Value.set(Set.of(
                                                Value.string("SELECT")
                                        ))
                                )
                        )
                )
        );
        Set<Access> accesses = new java.util.HashSet<>(
                appState.accesses()
        );
        accesses.add(publicAccess);
        return new PactState(Set.copyOf(accesses), Set.of());
    }

    private static void assertPublicDefaultsPreserved(
            PostgreSqlConfig config,
            String database,
            String schema
    ) throws SQLException {
        String sql = """
                SELECT
                    EXISTS (
                        SELECT 1
                        FROM pg_catalog.pg_database AS d
                        CROSS JOIN LATERAL pg_catalog.aclexplode(
                            COALESCE(
                                d.datacl,
                                pg_catalog.acldefault('d', d.datdba)
                            )
                        ) AS acl
                        WHERE d.datname = %s
                          AND acl.grantee = 0
                          AND acl.privilege_type = 'CONNECT'
                    ),
                    EXISTS (
                        SELECT 1
                        FROM pg_catalog.pg_proc AS p
                        JOIN pg_catalog.pg_namespace AS n
                          ON n.oid = p.pronamespace
                        CROSS JOIN LATERAL pg_catalog.aclexplode(
                            COALESCE(
                                p.proacl,
                                pg_catalog.acldefault('f', p.proowner)
                            )
                        ) AS acl
                        WHERE n.nspname = %s
                          AND p.proname = 'increment'
                          AND acl.grantee = 0
                          AND acl.privilege_type = 'EXECUTE'
                    )
                """.formatted(
                quoteLiteral(database),
                quoteLiteral(schema)
        );
        try (Connection connection = databaseConnection(
                config,
                database,
                config.username(),
                config.password()
        ); Statement statement = connection.createStatement();
             var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            assertTrue(rows.getBoolean(1));
            assertTrue(rows.getBoolean(2));
        }
    }

    private static void assertPublicDefaultsRevoked(
            PostgreSqlConfig config,
            String database,
            String schema
    ) throws SQLException {
        String sql = """
                SELECT
                    EXISTS (
                        SELECT 1
                        FROM pg_catalog.pg_database AS d
                        CROSS JOIN LATERAL pg_catalog.aclexplode(
                            COALESCE(
                                d.datacl,
                                pg_catalog.acldefault('d', d.datdba)
                            )
                        ) AS acl
                        WHERE d.datname = %s
                          AND acl.grantee = 0
                          AND acl.privilege_type IN ('CONNECT', 'TEMPORARY')
                    ),
                    EXISTS (
                        SELECT 1
                        FROM pg_catalog.pg_proc AS p
                        JOIN pg_catalog.pg_namespace AS n
                          ON n.oid = p.pronamespace
                        CROSS JOIN LATERAL pg_catalog.aclexplode(
                            COALESCE(
                                p.proacl,
                                pg_catalog.acldefault('f', p.proowner)
                            )
                        ) AS acl
                        WHERE n.nspname = %s
                          AND p.proname = 'increment'
                          AND acl.grantee = 0
                          AND acl.privilege_type = 'EXECUTE'
                    )
                """.formatted(
                quoteLiteral(database),
                quoteLiteral(schema)
        );
        try (Connection connection = databaseConnection(
                config,
                database,
                config.username(),
                config.password()
        ); Statement statement = connection.createStatement();
             var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            assertFalse(rows.getBoolean(1));
            assertFalse(rows.getBoolean(2));
        }
    }

    private static void assertNoGrantOptions(
            PostgreSqlConfig config,
            String database,
            String schema
    ) throws SQLException {
        String sql = """
                SELECT EXISTS (
                    SELECT 1
                    FROM pg_catalog.pg_namespace AS n
                    CROSS JOIN LATERAL pg_catalog.aclexplode(
                        COALESCE(
                            n.nspacl,
                            pg_catalog.acldefault('n', n.nspowner)
                        )
                    ) AS acl
                    WHERE n.nspname = %s
                      AND acl.grantee <> n.nspowner
                      AND acl.is_grantable
                    UNION ALL
                    SELECT 1
                    FROM pg_catalog.pg_class AS c
                    JOIN pg_catalog.pg_namespace AS n
                      ON n.oid = c.relnamespace
                    CROSS JOIN LATERAL pg_catalog.aclexplode(
                        COALESCE(
                            c.relacl,
                            pg_catalog.acldefault('r', c.relowner)
                        )
                    ) AS acl
                    WHERE n.nspname = %s
                      AND c.relname = %s
                      AND acl.grantee <> c.relowner
                      AND acl.is_grantable
                )
                """.formatted(
                quoteLiteral(schema),
                quoteLiteral(schema),
                quoteLiteral(TABLE)
        );
        try (Connection connection = databaseConnection(
                config,
                database,
                config.username(),
                config.password()
        ); Statement statement = connection.createStatement();
             var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            assertFalse(rows.getBoolean(1));
        }
    }

    private static void createDelegatedGrantFixture(
            PostgreSqlConfig config,
            String database,
            String owner,
            String grantManager,
            String grantManagerPassword,
            String appRole,
            String appPassword,
            String schema,
            boolean withGrantOption
    ) throws SQLException {
        String grantOption = withGrantOption ? " WITH GRANT OPTION" : "";
        try (Connection connection = adminConnection(config);
             Statement statement = connection.createStatement()) {
            statement.execute(
                    "CREATE ROLE " + quoteIdentifier(owner) + " NOLOGIN"
            );
            statement.execute(
                    "CREATE ROLE " + quoteIdentifier(grantManager)
                            + " LOGIN PASSWORD "
                            + quoteLiteral(grantManagerPassword)
            );
            statement.execute(
                    "CREATE ROLE " + quoteIdentifier(appRole)
                            + " LOGIN PASSWORD " + quoteLiteral(appPassword)
            );
            statement.execute(
                    "CREATE DATABASE " + quoteIdentifier(database)
                            + " OWNER " + quoteIdentifier(owner)
            );
        }

        try (Connection connection = databaseConnection(
                config,
                database,
                config.username(),
                config.password()
        ); Statement statement = connection.createStatement()) {
            statement.execute("SET ROLE " + quoteIdentifier(owner));
            statement.execute(
                    "GRANT CONNECT ON DATABASE " + quoteIdentifier(database)
                            + " TO " + quoteIdentifier(grantManager)
                            + grantOption
            );
            statement.execute(
                    "CREATE SCHEMA " + quoteIdentifier(schema)
            );
            statement.execute(
                    "CREATE TABLE " + quoteIdentifier(schema) + "."
                            + quoteIdentifier(TABLE) + " (id integer)"
            );
            statement.execute(
                    "GRANT USAGE ON SCHEMA " + quoteIdentifier(schema)
                            + " TO " + quoteIdentifier(grantManager)
                            + grantOption
            );
            statement.execute(
                    "GRANT SELECT ON TABLE " + quoteIdentifier(schema) + "."
                            + quoteIdentifier(TABLE) + " TO "
                            + quoteIdentifier(grantManager)
                            + grantOption
            );
        }
    }

    private static PactState grantsOnlyState(
            String database,
            String schema,
            String role
    ) {
        Access access = new Access(
                role,
                new Resource(
                        BACKEND_ID,
                        Map.of(
                                "database", database,
                                "schema", schema,
                                "table", TABLE
                        )
                ),
                Map.of(
                        "permissions",
                        Value.object(
                                Map.of(
                                        "database",
                                        Value.set(Set.of(
                                                Value.string("CONNECT")
                                        )),
                                        "schema",
                                        Value.set(Set.of(
                                                Value.string("USAGE")
                                        )),
                                        "table",
                                        Value.set(Set.of(
                                                Value.string("SELECT")
                                        ))
                                )
                        )
                )
        );
        return new PactState(Set.of(access), Set.of());
    }

    private static void assertNoElevatedRoleAttributes(
            PostgreSqlConfig config,
            String role
    ) throws SQLException {
        try (Connection connection = adminConnection(config);
             Statement statement = connection.createStatement();
             var rows = statement.executeQuery(
                     "SELECT rolsuper OR rolcreatedb OR rolcreaterole "
                             + "FROM pg_catalog.pg_roles WHERE rolname = "
                             + quoteLiteral(role)
             )) {
            assertTrue(rows.next());
            assertFalse(rows.getBoolean(1));
        }
    }

    private static void assertCanSelect(
            PostgreSqlConfig config,
            String database,
            String role,
            String password,
            String schema
    ) throws SQLException {
        try (Connection connection = databaseConnection(
                config,
                database,
                role,
                password
        ); Statement statement = connection.createStatement()) {
            statement.executeQuery(
                    "SELECT * FROM " + quoteIdentifier(schema) + "."
                            + quoteIdentifier(TABLE)
            ).close();
        }
    }

    private static void createDatabase(
            PostgreSqlConfig config,
            String database
    ) throws SQLException {
        try (Connection connection = adminConnection(config);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + quoteIdentifier(database));
        }
    }

    private static void createFixture(
            PostgreSqlConfig config,
            String database,
            String schema
    ) throws SQLException {
        try (Connection connection = databaseConnection(
                config,
                database,
                config.username(),
                config.password()
        ); Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + quoteIdentifier(schema));
            statement.execute(
                    "CREATE TABLE " + quoteIdentifier(schema) + "."
                            + quoteIdentifier(TABLE)
                            + " (id bigint GENERATED BY DEFAULT AS IDENTITY, "
                            + quoteIdentifier(COLUMN) + " text)"
            );
            statement.execute(
                    "CREATE FUNCTION " + quoteIdentifier(schema) + "."
                            + quoteIdentifier(FUNCTION)
                            + "(integer) RETURNS integer LANGUAGE SQL "
                            + "IMMUTABLE AS 'SELECT $1 + 1'"
            );
        }
    }

    private static PactState state(
            String database,
            String schema,
            String role,
            String password,
            String passwordVersion,
            Set<String> tablePrivileges
    ) {
        Map<String, String> target = Map.of(
                "database", database,
                "schema", schema,
                "table", TABLE,
                "column", COLUMN,
                "sequence", SEQUENCE,
                "function", FUNCTION + "(integer)"
        );
        Map<String, Value> permissions = new HashMap<>();
        permissions.put("database", Value.set(Set.of(Value.string("CONNECT"))));
        permissions.put("schema", Value.set(Set.of(Value.string("USAGE"))));
        permissions.put(
                "table",
                Value.set(tablePrivileges.stream().map(Value::string)
                        .collect(java.util.stream.Collectors.toSet()))
        );
        permissions.put("column", Value.set(Set.of(Value.string("UPDATE"))));
        permissions.put("sequence", Value.set(Set.of(Value.string("USAGE"))));
        permissions.put("function", Value.set(Set.of(Value.string("EXECUTE"))));

        Identity identity = new Identity(
                BACKEND_ID,
                role,
                true,
                "integration-test/" + role,
                passwordVersion,
                new SecretValue(password)
        );
        Access access = new Access(
                role,
                new Resource(BACKEND_ID, target),
                Map.of("permissions", Value.object(permissions))
        );
        return new PactState(Set.of(access), Set.of(identity));
    }

    private static PactState defaultPrivilegesState(
            String database,
            String schema,
            String creator,
            String role
    ) {
        Access defaults = new Access(
                role,
                new Resource(BACKEND_ID, Map.of(
                        "database", database,
                        "schema", schema
                )),
                Map.of(
                        "permissions", Value.object(Map.of(
                                "schema", Value.set(
                                        Set.of(Value.string("USAGE"))
                                )
                        )),
                        "defaultPrivileges", Value.object(Map.of(
                                "creator", Value.string(creator),
                                "permissions", Value.object(Map.of(
                                        "table", Value.set(Set.of(
                                                Value.string("SELECT"),
                                                Value.string("UPDATE")
                                        )),
                                        "sequence", Value.set(Set.of(
                                                Value.string("USAGE")
                                        )),
                                        "function", Value.set(Set.of(
                                                Value.string("EXECUTE")
                                        ))
                                ))
                        ))
                )
        );
        Access override = new Access(
                role,
                new Resource(BACKEND_ID, Map.of(
                        "database", database,
                        "schema", schema,
                        "table", "exception_table"
                )),
                Map.of("permissions", Value.object(Map.of(
                        "table", Value.set(Set.of(Value.string("SELECT")))
                )))
        );
        return new PactState(Set.of(defaults, override));
    }

    private static boolean hasPrivilege(
            PostgreSqlConfig config,
            String database,
            String query
    ) throws SQLException {
        try (Connection connection = databaseConnection(
                config,
                database,
                config.username(),
                config.password()
        ); Statement statement = connection.createStatement();
             var rows = statement.executeQuery(query)) {
            assertTrue(rows.next());
            return rows.getBoolean(1);
        }
    }

    private static String tableOwner(
            PostgreSqlConfig config,
            String database,
            String schema,
            String table
    ) throws SQLException {
        String sql = """
                SELECT pg_catalog.pg_get_userbyid(c.relowner)
                FROM pg_catalog.pg_class AS c
                JOIN pg_catalog.pg_namespace AS n ON n.oid = c.relnamespace
                WHERE n.nspname = %s AND c.relname = %s
                """.formatted(quoteLiteral(schema), quoteLiteral(table));
        try (Connection connection = databaseConnection(
                config, database, config.username(), config.password()
        ); Statement statement = connection.createStatement();
             var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }

    private static int tableRowCount(
            PostgreSqlConfig config,
            String database,
            String schema,
            String table
    ) throws SQLException {
        String sql = "SELECT count(*) FROM " + quoteIdentifier(schema) + "."
                + quoteIdentifier(table);
        try (Connection connection = databaseConnection(
                config, database, config.username(), config.password()
        ); Statement statement = connection.createStatement();
             var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }

    private static void assertTablePrivilege(
            PostgreSqlConfig config,
            String database,
            String role,
            String schema,
            String table,
            String privilege,
            boolean expected
    ) throws SQLException {
        assertEquals(
                expected,
                hasPrivilege(
                        config,
                        database,
                        "SELECT has_table_privilege("
                                + quoteLiteral(role) + ", "
                                + quoteLiteral(schema + "." + table) + ", "
                                + quoteLiteral(privilege) + ")"
                ),
                role + " table privilege " + privilege + " on "
                        + schema + "." + table
        );
    }

    private static void assertSequencePrivilege(
            PostgreSqlConfig config,
            String database,
            String role,
            String schema,
            String sequence,
            String privilege,
            boolean expected
    ) throws SQLException {
        assertEquals(
                expected,
                hasPrivilege(
                        config,
                        database,
                        "SELECT has_sequence_privilege("
                                + quoteLiteral(role) + ", "
                                + quoteLiteral(schema + "." + sequence) + ", "
                                + quoteLiteral(privilege) + ")"
                ),
                role + " sequence privilege " + privilege + " on "
                        + schema + "." + sequence
        );
    }

    private static Access permissionAccess(
            String role,
            Map<String, String> target,
            Map<String, Set<String>> permissions
    ) {
        return new Access(
                role,
                new Resource(BACKEND_ID, target),
                Map.of("permissions", Value.object(valueSets(permissions)))
        );
    }

    private static Access permissionAccessWithDefaults(
            String role,
            String database,
            String schema,
            String creator,
            Map<String, Set<String>> permissions,
            Map<String, Set<String>> defaultPermissions
    ) {
        return new Access(
                role,
                new Resource(BACKEND_ID, Map.of(
                        "database", database,
                        "schema", schema
                )),
                Map.of(
                        "permissions", Value.object(valueSets(permissions)),
                        "defaultPrivileges", Value.object(Map.of(
                                "creator", Value.string(creator),
                                "permissions", Value.object(
                                        valueSets(defaultPermissions)
                                )
                        ))
                )
        );
    }

    private static Map<String, Value> valueSets(
            Map<String, Set<String>> values
    ) {
        Map<String, Value> result = new HashMap<>();
        values.forEach((key, strings) -> result.put(
                key,
                Value.set(strings.stream()
                        .map(Value::string)
                        .collect(java.util.stream.Collectors.toSet()))
        ));
        return result;
    }

    private static Set<Grant> expectedGrants(
            String database,
            String schema,
            String role,
            String tablePrivilege
    ) {
        return Set.of(
                grant(new GrantTarget(database, null, null, null), role,
                        Privilege.CONNECT),
                grant(new GrantTarget(database, schema, null, null), role,
                        Privilege.USAGE),
                grant(new GrantTarget(database, schema, TABLE, null), role,
                        Privilege.valueOf(tablePrivilege)),
                grant(new GrantTarget(database, schema, TABLE, COLUMN), role,
                        Privilege.UPDATE),
                grant(new GrantTarget(
                        database, schema, null, null, SEQUENCE
                ), role, Privilege.USAGE),
                grant(new GrantTarget(
                        database,
                        schema,
                        null,
                        null,
                        null,
                        new RoutineSignature(FUNCTION, "integer")
                ), role, Privilege.EXECUTE)
        );
    }

    private static Grant grant(
            GrantTarget target,
            String role,
            Privilege privilege
    ) {
        return new Grant(target, role, privilege);
    }

    private static void assertCanLogin(
            PostgreSqlConfig config,
            String database,
            String role,
            String password
    ) throws SQLException {
        try (Connection connection = databaseConnection(
                config,
                database,
                role,
                password
        ); Statement statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SELECT current_user")) {
                assertTrue(rows.next());
                assertEquals(role, rows.getString(1));
            }
        }
    }

    private static Connection adminConnection(PostgreSqlConfig config)
            throws SQLException {
        return DriverManager.getConnection(
                config.jdbcUrl(),
                config.username(),
                config.password()
        );
    }

    private static Connection databaseConnection(
            PostgreSqlConfig config,
            String database,
            String username,
            String password
    ) throws SQLException {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(config.jdbcUrl());
        dataSource.setDatabaseName(database);
        return dataSource.getConnection(username, password);
    }

    private static void dropDatabaseAndRole(
            PostgreSqlConfig config,
            String database,
            String... roles
    ) throws SQLException {
        try (Connection connection = adminConnection(config);
             Statement statement = connection.createStatement()) {
            statement.execute(
                    "DROP DATABASE IF EXISTS " + quoteIdentifier(database)
            );
            for (String role : roles) {
                statement.execute(
                        "DROP ROLE IF EXISTS " + quoteIdentifier(role)
                );
            }
        }
    }

    private static String quoteLiteral(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private static String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Missing required integration-test environment variable: "
                            + name
            );
        }
        return value;
    }
}
