package io.github.pactproject.postgresql.client;

import io.github.pactproject.postgresql.model.GrantTarget;
import io.github.pactproject.postgresql.model.Ownership;
import io.github.pactproject.postgresql.model.RoutineSignature;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;

class JdbcOwnershipTest {
    @Test
    void quotesEveryOwnershipTargetAndRole() {
        assertEquals("ALTER DATABASE \"a\"\"b\" OWNER TO \"o\"\"r\"",
                JdbcOwnership.statement(new Ownership(new GrantTarget("a\"b", null, null, null), "o\"r")));
        assertEquals("ALTER SCHEMA \"s\" OWNER TO \"owner\"",
                JdbcOwnership.statement(new Ownership(new GrantTarget("db", "s", null, null), "owner")));
        assertEquals("ALTER TABLE \"s\".\"t\" OWNER TO \"owner\"", JdbcOwnership.statement(table("owner")));
        assertEquals("ALTER SEQUENCE \"s\".\"seq\" OWNER TO \"owner\"",
                JdbcOwnership.statement(sequence("owner")));
        assertEquals("ALTER FUNCTION \"s\".\"echo\"(integer) OWNER TO \"owner\"",
                JdbcOwnership.statement(routine("owner", "integer")));
        assertEquals("ALTER PROCEDURE \"s\".\"run\"() OWNER TO \"owner\"",
                JdbcOwnership.statement(new Ownership(new GrantTarget("db", "s", null, null, null,
                        null, new RoutineSignature("run", "")), "owner")));
    }

    @Test
    void preparationAndMatchingOwnershipNeverWrite() throws Exception {
        var writes = new ArrayList<String>();
        var connection = connection((sql, params) -> {
            if (sql.contains("pg_depend")) return List.of();
            if (sql.contains("WHERE rolname")) return List.of(List.of(1L));
            return List.of(List.of(11L, "owner"));
        }, writes);
        JdbcOwnership.resolve(connection, "db", Set.of(table("owner")), Set.of());
        JdbcOwnership.apply(connection, "db", Set.of(table("owner")));
        assertTrue(writes.isEmpty());
    }

    @Test
    void missingDuplicateAndMalformedReferencesFailBeforeWrites() throws Exception {
        for (List<List<Object>> catalog : List.<List<List<Object>>>of(List.of(),
                List.of(List.of(11L, "owner"), List.of(11L, "owner")),
                List.of(List.of(0L, "owner")), List.of(List.of(11L, "")))) {
            var writes = new ArrayList<String>();
            var connection = connection((sql, params) -> sql.contains("WHERE rolname")
                    ? List.of(List.of(1L)) : catalog, writes);
            assertThrows(SQLException.class,
                    () -> JdbcOwnership.apply(connection, "db", Set.of(table("owner"))));
            assertTrue(writes.isEmpty());
        }
        var writes = new ArrayList<String>();
        var missingRole = connection((sql, params) -> List.of(), writes);
        assertThrows(SQLException.class,
                () -> JdbcOwnership.resolve(missingRole, "db", Set.of(table("owner")), Set.of()));
        assertTrue(writes.isEmpty());
    }

    @Test
    void canonicalRoutineAliasesCannotHaveDifferentOwners() throws Exception {
        var writes = new ArrayList<String>();
        var connection = connection((sql, params) -> sql.contains("WHERE rolname")
                ? List.of(List.of(1L)) : List.of(List.of(20L, "old", "echo", "integer")), writes);
        assertThrows(SQLException.class, () -> JdbcOwnership.resolve(connection, "db",
                Set.of(routine("a", "integer"), routine("b", "int4")), Set.of()));
        assertTrue(writes.isEmpty());
    }

    @Test
    void sequenceDependenciesAreValidatedBeforeAnyOwnerWrites() throws Exception {
        var writes = new ArrayList<String>();
        var connection = connection((sql, params) -> {
            if (sql.contains("WHERE rolname")) return List.of(List.of(1L));
            if (sql.contains("pg_depend")) return List.of(List.of(12L, 11L, "old"));
            return List.of(List.of(params.get(2).equals("seq") ? 12L : 11L, "old"));
        }, writes);
        assertThrows(SQLException.class, () -> JdbcOwnership.apply(connection, "db",
                Set.of(table("a"), sequence("b"))));
        assertTrue(writes.isEmpty());
        JdbcOwnership.resolve(connection, "db", Set.of(table("a"), sequence("a")), Set.of());
        assertTrue(writes.isEmpty());
    }

    private static Ownership table(String role) {
        return new Ownership(new GrantTarget("db", "s", "t", null), role);
    }
    private static Ownership sequence(String role) {
        return new Ownership(new GrantTarget("db", "s", null, null, "seq"), role);
    }
    private static Ownership routine(String role, String arguments) {
        return new Ownership(new GrantTarget("db", "s", null, null, null,
                new RoutineSignature("echo", arguments)), role);
    }

    static Connection connection(BiFunction<String, Map<Integer, Object>, List<List<Object>>> catalog,
            List<String> writes) {
        return proxy(Connection.class, (method, args) -> switch (method) {
            case "prepareStatement" -> query((String) args[0], catalog, writes, PreparedStatement.class);
            case "createStatement" -> query(null, catalog, writes, Statement.class);
            default -> null;
        });
    }

    private static <T> T query(String sql, BiFunction<String, Map<Integer, Object>, List<List<Object>>> catalog,
            List<String> writes, Class<T> type) {
        var params = new HashMap<Integer, Object>();
        return proxy(type, (method, args) -> switch (method) {
            case "setString", "setLong" -> { params.put((Integer) args[0], args[1]); yield null; }
            case "executeQuery" -> rows(catalog.apply(sql == null ? (String) args[0] : sql, params));
            case "execute" -> { writes.add((String) args[0]); yield true; }
            default -> null;
        });
    }

    private static ResultSet rows(List<List<Object>> data) {
        int[] index = {-1};
        return proxy(ResultSet.class, (method, args) -> switch (method) {
            case "next" -> ++index[0] < data.size();
            case "getLong" -> ((Number) data.get(index[0]).get((Integer) args[0] - 1)).longValue();
            case "getString" -> (String) data.get(index[0]).get((Integer) args[0] - 1);
            case "getBoolean" -> (Boolean) data.get(index[0]).get((Integer) args[0] - 1);
            case "wasNull" -> false;
            default -> null;
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, BiFunction<String, Object[], Object> call) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (object, method, args) -> call.apply(method.getName(), args));
    }
}
