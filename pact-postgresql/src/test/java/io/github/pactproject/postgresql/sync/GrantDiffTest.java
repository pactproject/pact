package io.github.pactproject.postgresql.sync;

import io.github.pactproject.postgresql.model.Grant;
import io.github.pactproject.postgresql.model.GrantTarget;
import io.github.pactproject.postgresql.model.Privilege;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GrantDiffTest {
    @Test
    void createsMissingAndDeletesStaleGrants() {
        Grant keep = grant("alice", Privilege.CONNECT);
        Grant stale = grant("alice", Privilege.CREATE);
        Grant missing = grant("bob", Privilege.CONNECT);

        GrantDiff.Result diff = GrantDiff.diff(
                Set.of(keep, stale),
                Set.of(keep, missing)
        );

        assertEquals(Set.of(missing), diff.create());
        assertEquals(Set.of(stale), diff.delete());
    }

    private static Grant grant(String role, Privilege privilege) {
        return new Grant(
                new GrantTarget("analytics", null, null, null),
                role,
                privilege
        );
    }
}
