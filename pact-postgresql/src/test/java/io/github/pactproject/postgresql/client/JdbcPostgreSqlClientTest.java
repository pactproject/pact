package io.github.pactproject.postgresql.client;

import io.github.pactproject.postgresql.model.Grant;
import io.github.pactproject.postgresql.model.GrantTarget;
import io.github.pactproject.postgresql.model.RoutineSignature;
import io.github.pactproject.postgresql.model.Privilege;
import io.github.pactproject.postgresql.model.DefaultPrivilegeGrant;
import io.github.pactproject.postgresql.model.DefaultPrivilegeScope;
import io.github.pactproject.postgresql.model.DefaultPrivilegeType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JdbcPostgreSqlClientTest {
    @Test
    void replacesDatabaseInJdbcUrl() {
        assertEquals(
                "jdbc:postgresql://h:5432/analytics?ssl=true",
                JdbcPostgreSqlClient.jdbcUrlFor(
                        "jdbc:postgresql://h:5432/postgres?ssl=true",
                        "analytics"
                )
        );
        assertEquals(
                "jdbc:postgresql://h1:5432,h2:5432/my%20db",
                JdbcPostgreSqlClient.jdbcUrlFor(
                        "jdbc:postgresql://h1:5432,h2:5432",
                        "my db"
                )
        );
        assertEquals(
                "jdbc:postgresql:analytics",
                JdbcPostgreSqlClient.jdbcUrlFor(
                        "jdbc:postgresql:postgres",
                        "analytics"
                )
        );
    }

    @Test
    void buildsQuotedStatementsForEveryLevel() {
        assertEquals(
                "GRANT CONNECT ON DATABASE \"a\"\"b\" TO \"alice\"",
                JdbcPostgreSqlClient.statement("GRANT", grant(
                        new GrantTarget("a\"b", null, null, null),
                        Privilege.CONNECT
                ))
        );
        assertEquals(
                "GRANT CONNECT ON DATABASE \"analytics\" TO PUBLIC",
                JdbcPostgreSqlClient.statement("GRANT", new Grant(
                        new GrantTarget("analytics", null, null, null),
                        "PUBLIC",
                        Privilege.CONNECT
                ))
        );
        assertEquals(
                "REVOKE USAGE ON SCHEMA \"sales\" FROM \"alice\"",
                JdbcPostgreSqlClient.statement("REVOKE", grant(
                        new GrantTarget("a", "sales", null, null),
                        Privilege.USAGE
                ))
        );
        assertEquals(
                "GRANT SELECT ON TABLE \"sales\".\"orders\" TO \"alice\"",
                JdbcPostgreSqlClient.statement("GRANT", grant(
                        new GrantTarget("a", "sales", "orders", null),
                        Privilege.SELECT
                ))
        );
        assertEquals(
                "GRANT UPDATE (\"email\") ON TABLE \"sales\".\"orders\" TO \"alice\"",
                JdbcPostgreSqlClient.statement("GRANT", grant(
                        new GrantTarget("a", "sales", "orders", "email"),
                        Privilege.UPDATE
                ))
        );
        assertEquals(
                "GRANT USAGE ON SEQUENCE \"sales\".\"orders_id_seq\" TO \"alice\"",
                JdbcPostgreSqlClient.statement("GRANT", grant(
                        new GrantTarget(
                                "a", "sales", null, null, "orders_id_seq"),
                        Privilege.USAGE
                ))
        );
        assertEquals(
                "GRANT EXECUTE ON FUNCTION \"sales\".\"add\"(integer, integer) TO \"alice\"",
                JdbcPostgreSqlClient.statement("GRANT", grant(
                        new GrantTarget(
                                "a", "sales", null, null, null,
                                new RoutineSignature(
                                        "add", "integer, integer"
                                )
                        ),
                        Privilege.EXECUTE
                ))
        );
        assertEquals(
                "GRANT EXECUTE ON PROCEDURE \"sales\".\"refresh_orders\"(date) TO \"alice\"",
                JdbcPostgreSqlClient.statement("GRANT", grant(
                        new GrantTarget(
                                "a", "sales", null, null, null, null,
                                new RoutineSignature("refresh_orders", "date")
                        ),
                        Privilege.EXECUTE
                ))
        );
    }

    @Test
    void quotesPasswordAsPostgreSqlEscapeString() throws Exception {
        assertEquals(
                "E'pa''ss\\\\word'",
                JdbcPostgreSqlClient.quotePassword("pa'ss\\word")
        );
        assertThrows(
                java.sql.SQLException.class,
                () -> JdbcPostgreSqlClient.quotePassword("nul\0byte")
        );
    }

    @Test
    void buildsSchemaScopedDefaultPrivilegeStatements() {
        DefaultPrivilegeGrant grant = new DefaultPrivilegeGrant(
                new DefaultPrivilegeScope(
                        "analytics", "sales", "sales_migrator",
                        DefaultPrivilegeType.TABLES
                ),
                "PUBLIC",
                Privilege.SELECT
        );
        assertEquals(
                "ALTER DEFAULT PRIVILEGES FOR ROLE \"sales_migrator\" "
                        + "IN SCHEMA \"sales\" GRANT SELECT ON TABLES TO PUBLIC",
                JdbcPostgreSqlClient.defaultPrivilegeStatement(
                        "GRANT", grant, false
                )
        );
        assertEquals(
                "ALTER DEFAULT PRIVILEGES FOR ROLE \"sales_migrator\" "
                        + "IN SCHEMA \"sales\" REVOKE GRANT OPTION FOR SELECT "
                        + "ON TABLES FROM PUBLIC",
                JdbcPostgreSqlClient.defaultPrivilegeStatement(
                        "REVOKE", grant, true
                )
        );
    }

    private static Grant grant(GrantTarget target, Privilege privilege) {
        return new Grant(target, "alice", privilege);
    }
}
