package io.github.pactproject.postgresql.client;

import io.github.pactproject.postgresql.model.GrantLevel;
import io.github.pactproject.postgresql.model.GrantTarget;
import io.github.pactproject.postgresql.model.Ownership;
import io.github.pactproject.postgresql.model.RoutineSignature;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.github.pactproject.postgresql.client.JdbcPostgreSqlClient.quoteIdentifier;

/** Native exact-reference resolution and DDL; all state belongs to one call. */
final class JdbcOwnership {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(JdbcOwnership.class);

    private JdbcOwnership() { }

    static List<Resolved> resolve(Connection connection, String database,
            Set<Ownership> desired, Set<String> ensuredRoles) throws SQLException {
        Map<String, Resolved> resolved = new HashMap<>();
        for (Ownership ownership : desired.stream()
                .filter(value -> value.target().database().equals(database))
                .sorted(Comparator.comparing(value -> value.target().toString()))
                .toList()) {
            verifyRole(connection, ownership.role(), ensuredRoles);
            Resolved value = resolveObject(connection, ownership);
            Resolved existing = resolved.putIfAbsent(value.key(), value);
            if (existing != null && !existing.desired().role().equals(ownership.role())) {
                throw new SQLException("Conflicting PostgreSQL owners for canonical object " + value.desired().target());
            }
        }
        List<Resolved> result = new ArrayList<>(resolved.values());
        if (result.stream().anyMatch(value -> value.relation())) {
            verifySequenceDependencies(connection, result);
        }
        result.sort(Comparator.comparingInt((Resolved value) -> order(value.desired().target().level()))
                .thenComparing(value -> value.desired().target().toString()));
        return List.copyOf(result);
    }

    static void apply(Connection connection, String database, Set<Ownership> desired) throws SQLException {
        List<Resolved> plan = resolve(connection, database, desired, Set.of());
        for (Resolved value : plan) {
            // A preceding ALTER TABLE can already have transferred its owned sequences.
            Resolved actual = resolveObject(connection, value.desired());
            if (actual.actualOwner().equals(value.desired().role())) continue;
            String sql = statement(value.desired());
            log.debug("database={} action=set_owner result=started target={} previous_owner={} owner={} sql={}",
                    database, value.desired().target(), actual.actualOwner(), value.desired().role(), sql);
            try (var command = connection.createStatement()) {
                command.execute(sql);
            }
            log.debug("database={} action=set_owner result=executed target={} owner={}",
                    database, value.desired().target(), value.desired().role());
        }
        for (Resolved value : plan) {
            if (!resolveObject(connection, value.desired()).actualOwner().equals(value.desired().role())) {
                throw new SQLException("PostgreSQL did not apply desired owner for " + value.desired().target());
            }
        }
    }

    private static void verifyRole(Connection connection, String role, Set<String> ensuredRoles)
            throws SQLException {
        try (var query = connection.prepareStatement("SELECT oid FROM pg_catalog.pg_roles WHERE rolname = ?")) {
            query.setString(1, role);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) {
                    if (ensuredRoles.contains(role)) return;
                    throw new SQLException("PostgreSQL owner role does not exist: " + role);
                }
                requiredOid(rows, 1);
                if (rows.next()) throw new SQLException("Ambiguous PostgreSQL owner role: " + role);
            }
        }
    }

    private static Resolved resolveObject(Connection connection, Ownership ownership) throws SQLException {
        GrantTarget target = ownership.target();
        GrantLevel level = target.level();
        if (target.schema() != null && (target.schema().startsWith("pg_")
                || target.schema().equals("information_schema"))) {
            throw new SQLException("PostgreSQL system schema ownership is outside managed scope: " + target.schema());
        }
        String sql = switch (level) {
            case DATABASE -> "SELECT d.oid, r.rolname FROM pg_catalog.pg_database d "
                    + "JOIN pg_catalog.pg_roles r ON r.oid = d.datdba WHERE d.datname = ?";
            case SCHEMA -> "SELECT n.oid, r.rolname FROM pg_catalog.pg_namespace n "
                    + "JOIN pg_catalog.pg_roles r ON r.oid = n.nspowner WHERE n.nspname = ?";
            case TABLE, SEQUENCE -> "SELECT c.oid, r.rolname FROM pg_catalog.pg_class c "
                    + "JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace "
                    + "JOIN pg_catalog.pg_roles r ON r.oid = c.relowner "
                    + "WHERE n.nspname = ? AND c.relname = ? AND "
                    + (level == GrantLevel.TABLE ? "c.relkind IN ('r', 'p', 'v', 'm', 'f')" : "c.relkind = 'S'");
            case FUNCTION, PROCEDURE -> "SELECT p.oid, r.rolname, p.proname, "
                    + "pg_catalog.pg_get_function_identity_arguments(p.oid) FROM pg_catalog.pg_proc p "
                    + "JOIN pg_catalog.pg_roles r ON r.oid = p.proowner "
                    + "WHERE p.oid = pg_catalog.to_regprocedure(?) AND "
                    + (level == GrantLevel.FUNCTION ? "p.prokind IN ('f', 'w')" : "p.prokind = 'p'");
            case COLUMN -> throw new SQLException("PostgreSQL columns cannot have a separate owner");
        };
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            switch (level) {
                case DATABASE -> query.setString(1, target.database());
                case SCHEMA -> query.setString(1, target.schema());
                case TABLE, SEQUENCE -> {
                    query.setString(1, target.schema());
                    query.setString(2, level == GrantLevel.TABLE ? target.table() : target.sequence());
                }
                case FUNCTION, PROCEDURE -> query.setString(1, routineReference(target));
                default -> throw new SQLException("Unsupported ownership target");
            }
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) throw new SQLException("PostgreSQL ownership target does not exist or has wrong type: " + target);
                long oid = requiredOid(rows, 1);
                String owner = requiredString(rows, 2);
                if (level == GrantLevel.FUNCTION || level == GrantLevel.PROCEDURE) {
                    String name = requiredString(rows, 3);
                    String arguments = rows.getString(4);
                    if (arguments == null) throw new SQLException("Missing PostgreSQL routine identity arguments");
                    var signature = new RoutineSignature(name, arguments);
                    target = new GrantTarget(target.database(), target.schema(), null, null, null,
                            level == GrantLevel.FUNCTION ? signature : null,
                            level == GrantLevel.PROCEDURE ? signature : null);
                }
                if (rows.next()) throw new SQLException("Ambiguous PostgreSQL ownership target: " + target);
                String catalog = switch (level) {
                    case DATABASE -> "database";
                    case SCHEMA -> "namespace";
                    case TABLE, SEQUENCE -> "class";
                    default -> "proc";
                };
                return new Resolved(catalog + ":" + oid, oid, new Ownership(target, ownership.role()), owner);
            }
        }
    }

    private static void verifySequenceDependencies(Connection connection, List<Resolved> desired) throws SQLException {
        Map<Long, Resolved> relations = new HashMap<>();
        desired.stream().filter(Resolved::relation).forEach(value -> relations.put(value.oid(), value));
        // Native catalogs are read completely. Both serial and identity dependencies
        // constrain sequence ownership, even when only one endpoint is declared.
        try (var query = connection.createStatement(); var rows = query.executeQuery("""
                SELECT s.oid, t.oid, r.rolname
                FROM pg_catalog.pg_depend d
                JOIN pg_catalog.pg_class s ON s.oid = d.objid AND s.relkind = 'S'
                JOIN pg_catalog.pg_class t ON t.oid = d.refobjid
                JOIN pg_catalog.pg_roles r ON r.oid = t.relowner
                WHERE d.classid = 'pg_catalog.pg_class'::pg_catalog.regclass
                  AND d.refclassid = 'pg_catalog.pg_class'::pg_catalog.regclass
                  AND d.deptype IN ('a', 'i')
                """)) {
            Set<Long> seen = new java.util.HashSet<>();
            while (rows.next()) {
                long sequenceId = requiredOid(rows, 1);
                long tableId = requiredOid(rows, 2);
                String tableOwner = requiredString(rows, 3);
                if (!seen.add(sequenceId)) throw new SQLException("Duplicate PostgreSQL sequence ownership dependency");
                Resolved sequence = relations.get(sequenceId);
                Resolved table = relations.get(tableId);
                if (sequence != null && !sequence.desired().role().equals(
                        table == null ? tableOwner : table.desired().role())) {
                    throw new SQLException("Owned PostgreSQL sequence must have the same owner as its table: "
                            + sequence.desired().target());
                }
            }
        }
    }

    static String statement(Ownership ownership) {
        GrantTarget target = ownership.target();
        String subject = switch (target.level()) {
            case DATABASE -> "DATABASE " + quoteIdentifier(target.database());
            case SCHEMA -> "SCHEMA " + quoteIdentifier(target.schema());
            case TABLE -> "TABLE " + quoteIdentifier(target.schema()) + "." + quoteIdentifier(target.table());
            case SEQUENCE -> "SEQUENCE " + quoteIdentifier(target.schema()) + "." + quoteIdentifier(target.sequence());
            case FUNCTION -> "FUNCTION " + routineReference(target);
            case PROCEDURE -> "PROCEDURE " + routineReference(target);
            case COLUMN -> throw new IllegalArgumentException("PostgreSQL columns cannot have a separate owner");
        };
        return "ALTER " + subject + " OWNER TO " + quoteIdentifier(ownership.role());
    }

    private static String routineReference(GrantTarget target) {
        RoutineSignature signature = target.level() == GrantLevel.FUNCTION ? target.function() : target.procedure();
        return quoteIdentifier(target.schema()) + "." + quoteIdentifier(signature.name())
                + "(" + signature.arguments() + ")";
    }

    private static long requiredOid(ResultSet rows, int column) throws SQLException {
        long value = rows.getLong(column);
        if (rows.wasNull() || value <= 0) throw new SQLException("Malformed PostgreSQL ownership catalog OID");
        return value;
    }

    private static String requiredString(ResultSet rows, int column) throws SQLException {
        String value = rows.getString(column);
        if (value == null || value.isBlank()) throw new SQLException("Malformed PostgreSQL ownership catalog name");
        return value;
    }

    private static int order(GrantLevel level) {
        return switch (level) {
            case DATABASE -> 0;
            case SCHEMA -> 1;
            case TABLE -> 2;
            case FUNCTION, PROCEDURE -> 3;
            case SEQUENCE -> 4;
            case COLUMN -> 5;
        };
    }

    record Resolved(String key, long oid, Ownership desired, String actualOwner) {
        boolean relation() {
            return desired.target().level() == GrantLevel.TABLE || desired.target().level() == GrantLevel.SEQUENCE;
        }
    }
}
