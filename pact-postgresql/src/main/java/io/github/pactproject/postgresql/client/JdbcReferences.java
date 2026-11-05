package io.github.pactproject.postgresql.client;

import io.github.pactproject.api.Identity;
import io.github.pactproject.postgresql.model.*;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;

/** Exact, read-only references; no grants, passwords or accounts are changed here. */
final class JdbcReferences {
    private JdbcReferences() { }

    static void identities(Connection connection, Set<Identity> identities) throws SQLException {
        for (Identity identity : identities) {
            if (identity.passwordSource() != null && identity.password() == null) {
                throw new SQLException("Password Secret was not resolved for PostgreSQL role '" + identity.principal() + "'");
            }
            try (var query = connection.prepareStatement("SELECT oid, rolcanlogin FROM pg_catalog.pg_roles WHERE rolname = ?")) {
                query.setString(1, identity.principal());
                try (var rows = query.executeQuery()) {
                    if (!rows.next()) {
                        if (identity.ensure()) continue;
                        throw new SQLException("PostgreSQL role does not exist: " + identity.principal());
                    }
                    long oid = rows.getLong(1);
                    if (rows.wasNull() || oid <= 0) throw new SQLException("Malformed PostgreSQL role identity");
                    boolean login = rows.getBoolean(2);
                    if (rows.wasNull()) throw new SQLException("Malformed PostgreSQL role LOGIN attribute");
                    if (identity.password() != null && !login) {
                        throw new SQLException("PostgreSQL role must have LOGIN before setting a password: " + identity.principal());
                    }
                    if (rows.next()) throw new SQLException("Ambiguous PostgreSQL role: " + identity.principal());
                }
            }
        }
    }

    static void grants(Connection connection, String database, Set<Grant> grants,
            Set<DefaultPrivilegeGrant> defaults, Set<String> ensuredRoles) throws SQLException {
        Set<GrantTarget> targets = new HashSet<>();
        Set<String> grantees = new HashSet<>(), creators = new HashSet<>();
        for (Grant grant : grants) {
            if (!database.equals(grant.database())) continue;
            targets.add(grant.target());
            grantees.add(grant.role());
        }
        for (DefaultPrivilegeGrant grant : defaults) {
            if (!database.equals(grant.scope().database())) continue;
            targets.add(new GrantTarget(database, grant.scope().schema(), null, null));
            grantees.add(grant.role());
            creators.add(grant.scope().creator());
        }
        for (GrantTarget target : targets) target(connection, target);
        for (String role : grantees) if (!"PUBLIC".equals(role)) role(connection, role, ensuredRoles);
        for (String creator : creators) role(connection, creator, ensuredRoles);
    }

    private static void role(Connection connection, String role, Set<String> ensured) throws SQLException {
        if (ensured.contains(role)) return;
        requireOne(connection, "SELECT oid FROM pg_catalog.pg_roles WHERE rolname = ?",
                "role '" + role + "'", role);
    }

    private static void target(Connection connection, GrantTarget target) throws SQLException {
        switch (target.level()) {
            case DATABASE -> requireOne(connection, "SELECT oid FROM pg_catalog.pg_database WHERE datname = ?",
                    target.toString(), target.database());
            case SCHEMA -> requireOne(connection, "SELECT oid FROM pg_catalog.pg_namespace WHERE nspname = ?",
                    target.toString(), target.schema());
            case TABLE, SEQUENCE -> requireOne(connection,
                    "SELECT c.oid FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace "
                            + "WHERE n.nspname=? AND c.relname=? AND "
                            + (target.level() == GrantLevel.TABLE ? "c.relkind IN ('r','p','v','m','f')" : "c.relkind='S'"),
                    target.toString(), target.schema(), target.level() == GrantLevel.TABLE ? target.table() : target.sequence());
            case COLUMN -> requireOne(connection,
                    "SELECT c.oid FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace "
                            + "JOIN pg_catalog.pg_attribute a ON a.attrelid=c.oid WHERE n.nspname=? AND c.relname=? "
                            + "AND c.relkind IN ('r','p','v','m','f') AND a.attname=? AND a.attnum>0 AND NOT a.attisdropped",
                    target.toString(), target.schema(), target.table(), target.column());
            case FUNCTION, PROCEDURE -> {
                RoutineSignature signature = target.level() == GrantLevel.FUNCTION ? target.function() : target.procedure();
                String reference = JdbcPostgreSqlClient.quoteIdentifier(target.schema()) + "."
                        + JdbcPostgreSqlClient.quoteIdentifier(signature.name()) + "(" + signature.arguments() + ")";
                requireOne(connection, "SELECT oid FROM pg_catalog.pg_proc WHERE oid=pg_catalog.to_regprocedure(?) AND "
                                + (target.level() == GrantLevel.FUNCTION ? "prokind IN ('f','w')" : "prokind='p'"),
                        target.toString(), reference);
            }
        }
    }

    private static void requireOne(Connection connection, String sql, String description, String... values) throws SQLException {
        try (var query = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) query.setString(i + 1, values[i]);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) throw new SQLException("PostgreSQL reference does not exist: " + description);
                long oid = rows.getLong(1);
                if (rows.wasNull() || oid <= 0) throw new SQLException("Malformed PostgreSQL reference: " + description);
                if (rows.next()) throw new SQLException("Ambiguous PostgreSQL reference: " + description);
            }
        }
    }
}
