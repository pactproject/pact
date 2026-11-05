package io.github.pactproject.postgresql.client;

import io.github.pactproject.api.Identity;
import io.github.pactproject.api.SecretValue;
import io.github.pactproject.postgresql.model.*;
import org.junit.jupiter.api.Test;
import java.sql.SQLException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class JdbcReferencesTest {
    @Test void missingDuplicateAndMalformedNativeCatalogRowsFailWithoutWrites() {
        var desired = Set.of(new Grant(new GrantTarget("db", "s", "t", null), "reader", Privilege.SELECT));
        for (var rows : List.<List<List<Object>>>of(List.of(), List.of(List.of(0L)),
                List.of(List.of(11L), List.of(12L)))) {
            var writes = new ArrayList<String>();
            var connection = JdbcOwnershipTest.connection((sql, values) -> rows, writes);
            assertThrows(SQLException.class, () -> JdbcReferences.grants(connection, "db", desired, Set.of(), Set.of("reader")));
            assertTrue(writes.isEmpty());
        }
    }

    @Test void suppliedNamesAreBoundAndRoutineIdentifiersRemainQuotedInsideTheBoundReference() throws Exception {
        String schema = "schema\";--", table = "table\";--";
        var calls = new ArrayList<Map<Integer, Object>>();
        var writes = new ArrayList<String>();
        var connection = JdbcOwnershipTest.connection((sql, values) -> {
            assertFalse(sql.contains(schema));
            assertFalse(sql.contains(table));
            calls.add(Map.copyOf(values));
            return List.of(List.of(11L));
        }, writes);
        var routine = new GrantTarget("db", schema, null, null, null, new RoutineSignature("echo\";--", "integer"));
        JdbcReferences.grants(connection, "db", Set.of(
                new Grant(new GrantTarget("db", schema, table, null), "reader", Privilege.SELECT),
                new Grant(routine, "reader", Privilege.EXECUTE)), Set.of(), Set.of("reader"));
        assertTrue(calls.contains(Map.of(1, schema, 2, table)));
        assertTrue(calls.contains(Map.of(1, "\"schema\"\";--\".\"echo\"\";--\"(integer)")));
        assertTrue(writes.isEmpty());
    }

    @Test void missingAndNoLoginIdentityChecksNeverWriteOrExposePasswords() {
        var writes = new ArrayList<String>();
        var absent = JdbcOwnershipTest.connection((sql, values) -> List.of(), writes);
        var existing = JdbcOwnershipTest.connection((sql, values) -> List.of(List.of(11L, false)), writes);
        var require = new Identity("db", "role", false, null, null, null);
        assertThrows(SQLException.class, () -> JdbcReferences.identities(absent, Set.of(require)));
        var secret = new Identity("db", "role", true, "secret#password", "1", new SecretValue("do-not-print-this-password"));
        var failure = assertThrows(SQLException.class, () -> JdbcReferences.identities(existing, Set.of(secret)));
        assertTrue(failure.getMessage().contains("LOGIN"));
        assertFalse(failure.toString().contains("do-not-print-this-password"));
        assertTrue(writes.isEmpty());
    }
}
