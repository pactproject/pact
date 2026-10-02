package io.github.pactproject.postgresql.sync;

import io.github.pactproject.postgresql.model.DatabaseGrant;
import io.github.pactproject.postgresql.model.DatabasePrivilege;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DatabaseGrantDiffTest {
    @Test
    void computesCreateAndDeleteSets() {
        DatabaseGrant retained = new DatabaseGrant(
                "analytics",
                "alice",
                DatabasePrivilege.CONNECT
        );
        DatabaseGrant removed = new DatabaseGrant(
                "analytics",
                "bob",
                DatabasePrivilege.CREATE
        );
        DatabaseGrant added = new DatabaseGrant(
                "reporting",
                "carol",
                DatabasePrivilege.TEMPORARY
        );

        assertEquals(
                new DatabaseGrantDiff.GrantDiff(Set.of(added), Set.of(removed)),
                DatabaseGrantDiff.diff(
                        Set.of(retained, removed),
                        Set.of(retained, added)
                )
        );
    }
}
