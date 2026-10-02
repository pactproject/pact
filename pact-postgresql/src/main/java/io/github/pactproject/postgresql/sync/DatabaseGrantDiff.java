package io.github.pactproject.postgresql.sync;

import io.github.pactproject.postgresql.model.DatabaseGrant;

import java.util.HashSet;
import java.util.Set;

public final class DatabaseGrantDiff {
    private DatabaseGrantDiff() {
    }

    public static GrantDiff diff(
            Set<DatabaseGrant> actual,
            Set<DatabaseGrant> desired
    ) {
        Set<DatabaseGrant> create = new HashSet<>(desired);
        create.removeAll(actual);
        Set<DatabaseGrant> delete = new HashSet<>(actual);
        delete.removeAll(desired);
        return new GrantDiff(Set.copyOf(create), Set.copyOf(delete));
    }

    public record GrantDiff(
            Set<DatabaseGrant> create,
            Set<DatabaseGrant> delete
    ) {
        public GrantDiff {
            create = Set.copyOf(create);
            delete = Set.copyOf(delete);
        }
    }
}
