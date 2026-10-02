package io.github.pactproject.postgresql.client;

import io.github.pactproject.postgresql.PostgreSqlConfig;
import io.github.pactproject.postgresql.api.PostgreSqlClient;
import io.github.pactproject.postgresql.api.PostgreSqlClientException;
import io.github.pactproject.postgresql.model.DatabaseGrant;
import io.github.pactproject.postgresql.model.DatabasePrivilege;
import io.github.pactproject.postgresql.sync.DatabaseGrantDiff;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

public final class JdbcPostgreSqlClient implements PostgreSqlClient {
    private static final String MANAGED_GRANTS_QUERY = """
            SELECT grantee.rolname, acl.privilege_type, acl.is_grantable
            FROM pg_catalog.pg_database AS d
            CROSS JOIN LATERAL pg_catalog.aclexplode(
                COALESCE(
                    d.datacl,
                    pg_catalog.acldefault('d', d.datdba)
                )
            ) AS acl
            JOIN pg_catalog.pg_roles AS grantee ON grantee.oid = acl.grantee
            WHERE d.datname = ?
              AND acl.grantor = (
                  SELECT oid FROM pg_catalog.pg_roles WHERE rolname = current_user
              )
              AND acl.grantee <> 0
              AND acl.grantee <> (
                  SELECT oid FROM pg_catalog.pg_roles WHERE rolname = current_user
              )
            """;

    private final PostgreSqlConfig config;

    public JdbcPostgreSqlClient(PostgreSqlConfig config) {
        this.config = config;
    }

    @Override
    public Set<DatabaseGrant> getManagedGrants(Set<String> databases)
            throws PostgreSqlClientException {
        if (databases.isEmpty()) {
            return Set.of();
        }
        try (Connection connection = connect()) {
            return loadManagedGrants(connection, databases);
        }
        catch (SQLException e) {
            throw new PostgreSqlClientException(
                    "Failed to read PACT-owned PostgreSQL database grants",
                    e
            );
        }
    }

    @Override
    public void synchronize(
            Set<String> databases,
            Set<DatabaseGrant> desired
    ) throws PostgreSqlClientException {
        if (databases.isEmpty()) {
            if (!desired.isEmpty()) {
                throw new PostgreSqlClientException(
                        "Cannot apply PostgreSQL grants without target databases"
                );
            }
            return;
        }
        for (DatabaseGrant grant : desired) {
            if (!databases.contains(grant.database())) {
                throw new PostgreSqlClientException(
                        "Desired grant is outside its PostgreSQL reconciliation scope"
                );
            }
        }

        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                Set<DatabaseGrant> actual =
                        loadManagedGrants(connection, databases);
                DatabaseGrantDiff.GrantDiff diff =
                        DatabaseGrantDiff.diff(actual, desired);
                for (DatabaseGrant grant : diff.delete()) {
                    executeGrantStatement(connection, "REVOKE", grant);
                }
                for (DatabaseGrant grant : diff.create()) {
                    executeGrantStatement(connection, "GRANT", grant);
                }
                connection.commit();
            }
            catch (SQLException | RuntimeException e) {
                rollback(connection, e);
                throw e;
            }
        }
        catch (SQLException e) {
            throw new PostgreSqlClientException(
                    "Failed to synchronize PostgreSQL database grants",
                    e
            );
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(
                config.jdbcUrl(),
                config.username(),
                config.password()
        );
    }

    private Set<DatabaseGrant> loadManagedGrants(
            Connection connection,
            Set<String> databases
    ) throws SQLException {
        Set<DatabaseGrant> result = new HashSet<>();
        try (PreparedStatement statement =
                     connection.prepareStatement(MANAGED_GRANTS_QUERY)) {
            for (String database : databases) {
                statement.setString(1, database);
                try (ResultSet rows = statement.executeQuery()) {
                    boolean found = false;
                    while (rows.next()) {
                        found = true;
                        String role = rows.getString(1);
                        DatabasePrivilege privilege =
                                privilegeFromCatalog(rows.getString(2));
                        if (rows.getBoolean(3)) {
                            throw new SQLException(
                                    "PACT grantor has a grant-option privilege on database '"
                                            + database
                                            + "'; grant options are outside the managed contract"
                            );
                        }
                        result.add(new DatabaseGrant(
                                database,
                                role,
                                privilege
                        ));
                    }
                    if (!found && !databaseExists(connection, database)) {
                        throw new SQLException(
                                "PostgreSQL database does not exist: " + database
                        );
                    }
                }
            }
        }
        return Set.copyOf(result);
    }

    private boolean databaseExists(Connection connection, String database)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM pg_catalog.pg_database WHERE datname = ?"
        )) {
            statement.setString(1, database);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    private void executeGrantStatement(
            Connection connection,
            String operation,
            DatabaseGrant grant
    ) throws SQLException {
        String sql = operation + " " + grant.privilege().name()
                + " ON DATABASE " + quoteIdentifier(grant.database())
                + ("GRANT".equals(operation) ? " TO " : " FROM ")
                + quoteIdentifier(grant.role());
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    static String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private static DatabasePrivilege privilegeFromCatalog(String privilege)
            throws SQLException {
        try {
            return DatabasePrivilege.parse(privilege);
        }
        catch (IllegalArgumentException e) {
            throw new SQLException(
                    "Unsupported PostgreSQL database ACL privilege: " + privilege,
                    e
            );
        }
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
