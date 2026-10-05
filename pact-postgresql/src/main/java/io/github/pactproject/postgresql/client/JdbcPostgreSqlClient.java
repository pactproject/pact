package io.github.pactproject.postgresql.client;

import io.github.pactproject.postgresql.PostgreSqlConfig;
import io.github.pactproject.postgresql.api.PostgreSqlClient;
import io.github.pactproject.postgresql.api.PostgreSqlClientException;
import io.github.pactproject.postgresql.model.Grant;
import io.github.pactproject.postgresql.model.GrantLevel;
import io.github.pactproject.postgresql.model.GrantTarget;
import io.github.pactproject.postgresql.model.RoutineSignature;
import io.github.pactproject.postgresql.model.Privilege;
import io.github.pactproject.postgresql.sync.GrantDiff;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JdbcPostgreSqlClient implements PostgreSqlClient {
    private static final Pattern JDBC_URL = Pattern.compile(
            "^(jdbc:postgresql:(?://[^/?]*)?)(?:/?([^?]*))?(\\?.*)?$"
    );

    private static final String GRANTOR =
            "(SELECT oid FROM pg_catalog.pg_roles WHERE rolname = current_user)";
    private static final String OWNED_BY_GRANTOR =
            "acl.grantor = " + GRANTOR
                    + " AND acl.grantee <> 0 AND acl.grantee <> " + GRANTOR;
    private static final String USER_SCHEMA =
            "n.nspname NOT LIKE 'pg!_%' ESCAPE '!' "
                    + "AND n.nspname <> 'information_schema'";
    private static final String RELATION_KINDS =
            "c.relkind IN ('r', 'p', 'v', 'm', 'f')";

    private static final String DATABASE_GRANTS = """
            SELECT grantee.rolname, acl.privilege_type, acl.is_grantable
            FROM pg_catalog.pg_database AS d
            CROSS JOIN LATERAL pg_catalog.aclexplode(
                COALESCE(d.datacl, pg_catalog.acldefault('d', d.datdba))
            ) AS acl
            JOIN pg_catalog.pg_roles AS grantee ON grantee.oid = acl.grantee
            WHERE d.datname = current_database() AND\s""" + OWNED_BY_GRANTOR;

    private static final String SCHEMA_GRANTS = """
            SELECT n.nspname, grantee.rolname, acl.privilege_type, acl.is_grantable
            FROM pg_catalog.pg_namespace AS n
            CROSS JOIN LATERAL pg_catalog.aclexplode(
                COALESCE(n.nspacl, pg_catalog.acldefault('n', n.nspowner))
            ) AS acl
            JOIN pg_catalog.pg_roles AS grantee ON grantee.oid = acl.grantee
            WHERE\s""" + USER_SCHEMA + " AND " + OWNED_BY_GRANTOR;

    private static final String TABLE_GRANTS = """
            SELECT n.nspname, c.relname, grantee.rolname,
                   acl.privilege_type, acl.is_grantable
            FROM pg_catalog.pg_class AS c
            JOIN pg_catalog.pg_namespace AS n ON n.oid = c.relnamespace
            CROSS JOIN LATERAL pg_catalog.aclexplode(
                COALESCE(c.relacl, pg_catalog.acldefault('r', c.relowner))
            ) AS acl
            JOIN pg_catalog.pg_roles AS grantee ON grantee.oid = acl.grantee
            WHERE\s""" + RELATION_KINDS + " AND " + USER_SCHEMA
            + " AND " + OWNED_BY_GRANTOR;

    private static final String COLUMN_GRANTS = """
            SELECT n.nspname, c.relname, a.attname, grantee.rolname,
                   acl.privilege_type, acl.is_grantable
            FROM pg_catalog.pg_attribute AS a
            JOIN pg_catalog.pg_class AS c ON c.oid = a.attrelid
            JOIN pg_catalog.pg_namespace AS n ON n.oid = c.relnamespace
            CROSS JOIN LATERAL pg_catalog.aclexplode(a.attacl) AS acl
            JOIN pg_catalog.pg_roles AS grantee ON grantee.oid = acl.grantee
            WHERE a.attnum > 0 AND NOT a.attisdropped AND\s"""
            + RELATION_KINDS + " AND " + USER_SCHEMA
            + " AND " + OWNED_BY_GRANTOR;

    private static final String SEQUENCE_GRANTS = """
            SELECT n.nspname, c.relname, grantee.rolname,
                   acl.privilege_type, acl.is_grantable
            FROM pg_catalog.pg_class AS c
            JOIN pg_catalog.pg_namespace AS n ON n.oid = c.relnamespace
            CROSS JOIN LATERAL pg_catalog.aclexplode(
                COALESCE(c.relacl, pg_catalog.acldefault('S', c.relowner))
            ) AS acl
            JOIN pg_catalog.pg_roles AS grantee ON grantee.oid = acl.grantee
            WHERE c.relkind = 'S' AND\s""" + USER_SCHEMA
            + " AND " + OWNED_BY_GRANTOR;

    private static final String FUNCTION_GRANTS = """
            SELECT n.nspname, p.proname,
                   pg_catalog.pg_get_function_identity_arguments(p.oid),
                   grantee.rolname, acl.privilege_type, acl.is_grantable
            FROM pg_catalog.pg_proc AS p
            JOIN pg_catalog.pg_namespace AS n ON n.oid = p.pronamespace
            CROSS JOIN LATERAL pg_catalog.aclexplode(
                COALESCE(p.proacl, pg_catalog.acldefault('f', p.proowner))
            ) AS acl
            JOIN pg_catalog.pg_roles AS grantee ON grantee.oid = acl.grantee
            WHERE p.prokind IN ('f', 'a', 'w') AND\s""" + USER_SCHEMA
            + " AND " + OWNED_BY_GRANTOR;

    private static final String PROCEDURE_GRANTS = """
            SELECT n.nspname, p.proname,
                   pg_catalog.pg_get_function_identity_arguments(p.oid),
                   grantee.rolname, acl.privilege_type, acl.is_grantable
            FROM pg_catalog.pg_proc AS p
            JOIN pg_catalog.pg_namespace AS n ON n.oid = p.pronamespace
            CROSS JOIN LATERAL pg_catalog.aclexplode(
                COALESCE(p.proacl, pg_catalog.acldefault('f', p.proowner))
            ) AS acl
            JOIN pg_catalog.pg_roles AS grantee ON grantee.oid = acl.grantee
            WHERE p.prokind = 'p' AND\s""" + USER_SCHEMA
            + " AND " + OWNED_BY_GRANTOR;

    private static final String LIST_SCHEMAS =
            "SELECT n.nspname FROM pg_catalog.pg_namespace AS n WHERE "
                    + USER_SCHEMA;
    private static final String LIST_TABLES =
            "SELECT c.relname FROM pg_catalog.pg_class AS c "
                    + "JOIN pg_catalog.pg_namespace AS n ON n.oid = c.relnamespace "
                    + "WHERE n.nspname = ? AND " + RELATION_KINDS;
    private static final String LIST_COLUMNS =
            "SELECT a.attname FROM pg_catalog.pg_attribute AS a "
                    + "JOIN pg_catalog.pg_class AS c ON c.oid = a.attrelid "
                    + "JOIN pg_catalog.pg_namespace AS n ON n.oid = c.relnamespace "
                    + "WHERE n.nspname = ? AND c.relname = ? "
                    + "AND a.attnum > 0 AND NOT a.attisdropped";
    private static final String LIST_SEQUENCES =
            "SELECT c.relname FROM pg_catalog.pg_class AS c "
                    + "JOIN pg_catalog.pg_namespace AS n ON n.oid = c.relnamespace "
                    + "WHERE n.nspname = ? AND c.relkind = 'S'";

    private final PostgreSqlConfig config;

    public JdbcPostgreSqlClient(PostgreSqlConfig config) {
        this.config = config;
    }

    @Override
    public Set<Grant> getManagedGrants(Set<String> databases)
            throws PostgreSqlClientException {
        Set<Grant> result = new HashSet<>();
        for (String database : databases) {
            try (Connection connection = connect(database)) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("SET search_path TO pg_catalog");
                }
                result.addAll(loadManagedGrants(connection, database));
            }
            catch (SQLException e) {
                throw new PostgreSqlClientException(
                        "Failed to read PACT-owned PostgreSQL grants in database '"
                                + database + "'",
                        e
                );
            }
        }
        return Set.copyOf(result);
    }

    @Override
    public void synchronize(Set<String> databases, Set<Grant> desired)
            throws PostgreSqlClientException {
        for (Grant grant : desired) {
            if (!databases.contains(grant.database())) {
                throw new PostgreSqlClientException(
                        "Desired grant is outside its PostgreSQL reconciliation scope"
                );
            }
        }
        // Each database needs its own connection, so the sync is atomic per
        // database only; the backend transaction compensates across databases.
        for (String database : databases) {
            try (Connection connection = connect(database)) {
                connection.setAutoCommit(false);
                try {
                    synchronizeDatabase(connection, database, desired);
                    connection.commit();
                }
                catch (SQLException | RuntimeException e) {
                    rollback(connection, e);
                    throw e;
                }
            }
            catch (SQLException e) {
                throw new PostgreSqlClientException(
                        "Failed to synchronize PostgreSQL grants in database '"
                                + database + "'",
                        e
                );
            }
        }
    }

    private void synchronizeDatabase(
            Connection connection,
            String database,
            Set<Grant> desired
    ) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET LOCAL search_path TO pg_catalog");
        }
        Set<Grant> wanted = expand(
                connection,
                database,
                desired.stream()
                        .filter(grant -> grant.database().equals(database))
                        .toList()
        );
        Set<Grant> actual = loadManagedGrants(connection, database);
        GrantDiff.Result diff = GrantDiff.diff(actual, wanted);
        if (diff.delete().isEmpty() && diff.create().isEmpty()) {
            return;
        }
        for (Grant grant : diff.delete()) {
            execute(connection, "REVOKE", grant);
        }
        // Re-read after revokes before granting to keep the ACL diff authoritative.
        actual = loadManagedGrants(connection, database);
        for (Grant grant : GrantDiff.diff(actual, wanted).create()) {
            execute(connection, "GRANT", grant);
        }
    }

    static String jdbcUrlFor(String jdbcUrl, String database) {
        Matcher matcher = JDBC_URL.matcher(jdbcUrl);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Unsupported PostgreSQL jdbc-url");
        }
        String prefix = matcher.group(1);
        String query = matcher.group(3) == null ? "" : matcher.group(3);
        String encoded = URLEncoder.encode(database, StandardCharsets.UTF_8)
                .replace("+", "%20");
        String separator = prefix.contains("//") ? "/" : "";
        return prefix + separator + encoded + query;
    }

    private Connection connect(String database) throws SQLException {
        return DriverManager.getConnection(
                jdbcUrlFor(config.jdbcUrl(), database),
                config.username(),
                config.password()
        );
    }

    private Set<Grant> loadManagedGrants(Connection connection, String database)
            throws SQLException {
        Set<Grant> result = new HashSet<>();
        try (Statement statement = connection.createStatement()) {
            try (ResultSet rows = statement.executeQuery(DATABASE_GRANTS)) {
                while (rows.next()) {
                    result.add(grant(
                            new GrantTarget(database, null, null, null),
                            rows.getString(1), rows.getString(2),
                            rows.getBoolean(3)
                    ));
                }
            }
            try (ResultSet rows = statement.executeQuery(SCHEMA_GRANTS)) {
                while (rows.next()) {
                    result.add(grant(
                            new GrantTarget(database, rows.getString(1), null, null),
                            rows.getString(2), rows.getString(3),
                            rows.getBoolean(4)
                    ));
                }
            }
            try (ResultSet rows = statement.executeQuery(TABLE_GRANTS)) {
                while (rows.next()) {
                    result.add(grant(
                            new GrantTarget(
                                    database,
                                    rows.getString(1),
                                    rows.getString(2),
                                    null
                            ),
                            rows.getString(3), rows.getString(4),
                            rows.getBoolean(5)
                    ));
                }
            }
            try (ResultSet rows = statement.executeQuery(COLUMN_GRANTS)) {
                while (rows.next()) {
                    result.add(grant(
                            new GrantTarget(
                                    database,
                                    rows.getString(1),
                                    rows.getString(2),
                                    rows.getString(3)
                            ),
                            rows.getString(4), rows.getString(5),
                            rows.getBoolean(6)
                    ));
                }
            }
            try (ResultSet rows = statement.executeQuery(SEQUENCE_GRANTS)) {
                while (rows.next()) {
                    result.add(grant(
                            new GrantTarget(
                                    database,
                                    rows.getString(1),
                                    null,
                                    null,
                                    rows.getString(2)
                            ),
                            rows.getString(3), rows.getString(4),
                            rows.getBoolean(5)
                    ));
                }
            }
            try (ResultSet rows = statement.executeQuery(FUNCTION_GRANTS)) {
                while (rows.next()) {
                    String name = rows.getString(2);
                    String arguments = rows.getString(3);
                    result.add(grant(
                            new GrantTarget(
                                    database,
                                    rows.getString(1),
                                    null,
                                    null,
                                    null,
                                    RoutineSignature.parse(
                                            quoteIdentifier(name)
                                                    + "(" + arguments + ")"
                                    ),
                                    null
                            ),
                            rows.getString(4), rows.getString(5),
                            rows.getBoolean(6)
                    ));
                }
            }
            try (ResultSet rows = statement.executeQuery(PROCEDURE_GRANTS)) {
                while (rows.next()) {
                    String name = rows.getString(2);
                    String arguments = rows.getString(3);
                    result.add(grant(
                            new GrantTarget(
                                    database,
                                    rows.getString(1),
                                    null,
                                    null,
                                    null,
                                    null,
                                    RoutineSignature.parse(
                                            quoteIdentifier(name)
                                                    + "(" + arguments + ")"
                                    )
                            ),
                            rows.getString(4), rows.getString(5),
                            rows.getBoolean(6)
                    ));
                }
            }
        }
        return Set.copyOf(result);
    }

    private static Grant grant(
            GrantTarget target,
            String role,
            String privilege,
            boolean grantable
    ) throws SQLException {
        if (grantable) {
            throw new SQLException(
                    "PACT grantor has a grant-option privilege on "
                            + target.level().key() + " in database '"
                            + target.database()
                            + "'; grant options are outside the managed contract"
            );
        }
        try {
            return new Grant(
                    target,
                    role,
                    Privilege.parse(target.level(), privilege)
            );
        }
        catch (IllegalArgumentException e) {
            throw new SQLException(
                    "Unsupported PostgreSQL ACL privilege: " + privilege, e
            );
        }
    }

    private Set<Grant> expand(
            Connection connection,
            String database,
            List<Grant> grants
    ) throws SQLException {
        Set<Grant> result = new HashSet<>();
        for (Grant grant : grants) {
            GrantTarget target = grant.target();
            if (target.level() == GrantLevel.FUNCTION
                    || target.level() == GrantLevel.PROCEDURE) {
                boolean procedure = target.level() == GrantLevel.PROCEDURE;
                for (String schema : names(target.schema(),
                        () -> listSchemas(connection))) {
                    Grant schemaGrant = new Grant(
                            new GrantTarget(
                                    target.database(),
                                    schema,
                                    null,
                                    null,
                                    null,
                                    procedure ? null : target.function(),
                                    procedure ? target.procedure() : null
                            ),
                            grant.role(),
                            grant.privilege()
                    );
                    result.add(resolveRoutine(connection, schemaGrant));
                }
                continue;
            }
            if (!target.hasWildcard()) {
                result.add(grant);
                continue;
            }
            for (String schema : names(target.schema(),
                    () -> listSchemas(connection))) {
                switch (target.level()) {
                    case DATABASE -> result.add(grant);
                    case SCHEMA -> result.add(new Grant(
                            new GrantTarget(database, schema, null, null),
                            grant.role(),
                            grant.privilege()
                    ));
                    case TABLE -> {
                        for (String table : names(target.table(),
                                () -> listTables(connection, schema))) {
                            result.add(new Grant(
                                    new GrantTarget(
                                            database, schema, table, null),
                                    grant.role(),
                                    grant.privilege()
                            ));
                        }
                    }
                    case COLUMN -> {
                        for (String table : names(target.table(),
                                () -> listTables(connection, schema))) {
                            for (String column : names(target.column(),
                                    () -> listColumns(connection, schema, table))) {
                                result.add(new Grant(
                                        new GrantTarget(
                                                database, schema, table, column),
                                        grant.role(),
                                        grant.privilege()
                                ));
                            }
                        }
                    }
                    case SEQUENCE -> {
                        for (String sequence : names(target.sequence(),
                                () -> listSequences(connection, schema))) {
                            result.add(new Grant(
                                    new GrantTarget(
                                            database, schema, null, null, sequence),
                                    grant.role(),
                                    grant.privilege()
                            ));
                        }
                    }
                    case FUNCTION -> throw new IllegalStateException(
                            "Routine grants must be resolved before wildcard expansion"
                    );
                    case PROCEDURE -> throw new IllegalStateException(
                            "Routine grants must be resolved before wildcard expansion"
                    );
                }
            }
        }
        return result;
    }

    private Grant resolveRoutine(Connection connection, Grant grant)
            throws SQLException {
        boolean procedure = grant.target().level() == GrantLevel.PROCEDURE;
        RoutineSignature signature = procedure
                ? grant.target().procedure()
                : grant.target().function();
        String identity = quoteIdentifier(grant.target().schema()) + "."
                + quoteIdentifier(signature.name()) + "("
                + signature.arguments() + ")";
        long oid;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT pg_catalog.to_regprocedure(?)::oid"
        )) {
            statement.setString(1, identity);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next() || rows.getObject(1) == null) {
                    throw new SQLException(
                            "PostgreSQL routine does not exist: "
                                    + grant.target().schema() + "."
                                    + signature.display()
                    );
                }
                oid = rows.getLong(1);
            }
        }

        String kindPredicate = procedure
                ? "p.prokind = 'p'"
                : "p.prokind IN ('f', 'a', 'w')";
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT p.proname,
                       pg_catalog.pg_get_function_identity_arguments(p.oid)
                FROM pg_catalog.pg_proc AS p
                WHERE p.oid = ? AND """ + kindPredicate)) {
            statement.setLong(1, oid);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException(
                            "Target is not a PostgreSQL "
                                    + (procedure ? "procedure: " : "function: ")
                                    + grant.target().schema() + "."
                                    + signature.display()
                    );
                }
                RoutineSignature canonical = RoutineSignature.parse(
                        quoteIdentifier(rows.getString(1))
                                + "(" + rows.getString(2) + ")"
                );
                return new Grant(
                        new GrantTarget(
                                grant.target().database(),
                                grant.target().schema(),
                                null,
                                null,
                                null,
                                procedure ? null : canonical,
                                procedure ? canonical : null
                        ),
                        grant.role(),
                        grant.privilege()
                );
            }
        }
    }

    private interface Lister {
        List<String> list() throws SQLException;
    }

    private static List<String> names(String value, Lister lister)
            throws SQLException {
        if (value == null) {
            return Collections.singletonList(null);
        }
        if (GrantTarget.WILDCARD.equals(value)) {
            return lister.list();
        }
        return List.of(value);
    }

    private List<String> listSchemas(Connection connection)
            throws SQLException {
        return query(connection, LIST_SCHEMAS);
    }

    private List<String> listTables(Connection connection, String schema)
            throws SQLException {
        return query(connection, LIST_TABLES, schema);
    }

    private List<String> listColumns(
            Connection connection,
            String schema,
            String table
    ) throws SQLException {
        return query(connection, LIST_COLUMNS, schema, table);
    }

    private List<String> listSequences(Connection connection, String schema)
            throws SQLException {
        return query(connection, LIST_SEQUENCES, schema);
    }

    private static List<String> query(
            Connection connection,
            String sql,
            String... parameters
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setString(i + 1, parameters[i]);
            }
            try (ResultSet rows = statement.executeQuery()) {
                List<String> result = new ArrayList<>();
                while (rows.next()) {
                    result.add(rows.getString(1));
                }
                return result;
            }
        }
    }

    private void execute(Connection connection, String operation, Grant grant)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(statement(operation, grant));
        }
    }

    static String statement(String operation, Grant grant) {
        GrantTarget target = grant.target();
        String privilege = grant.privilege().name();
        String relation = target.level() == GrantLevel.TABLE
                || target.level() == GrantLevel.COLUMN
                ? quoteIdentifier(target.schema()) + "."
                + quoteIdentifier(target.table())
                : null;
        String subject = switch (target.level()) {
            case DATABASE -> privilege + " ON DATABASE "
                    + quoteIdentifier(target.database());
            case SCHEMA -> privilege + " ON SCHEMA "
                    + quoteIdentifier(target.schema());
            case TABLE -> privilege + " ON TABLE " + relation;
            case COLUMN -> privilege + " (" + quoteIdentifier(target.column())
                    + ") ON TABLE " + relation;
            case SEQUENCE -> privilege + " ON SEQUENCE "
                    + quoteIdentifier(target.schema()) + "."
                    + quoteIdentifier(target.sequence());
            case FUNCTION -> privilege + " ON FUNCTION "
                    + quoteIdentifier(target.schema()) + "."
                    + quoteIdentifier(target.function().name()) + "("
                    + target.function().arguments() + ")";
            case PROCEDURE -> privilege + " ON PROCEDURE "
                    + quoteIdentifier(target.schema()) + "."
                    + quoteIdentifier(target.procedure().name()) + "("
                    + target.procedure().arguments() + ")";
        };
        return operation + " " + subject
                + ("GRANT".equals(operation) ? " TO " : " FROM ")
                + quoteIdentifier(grant.role());
    }

    static String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private static void rollback(Connection connection, Throwable failure) {
        try {
            connection.rollback();
        }
        catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }
}
