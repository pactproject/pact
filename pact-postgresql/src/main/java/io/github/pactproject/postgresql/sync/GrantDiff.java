package io.github.pactproject.postgresql.sync;

import io.github.pactproject.postgresql.model.Grant;

import java.util.HashSet;
import java.util.Set;

public final class GrantDiff {
    private GrantDiff() {
    }

    public static Result diff(Set<Grant> actual, Set<Grant> desired) {
        Set<Grant> create = new HashSet<>(desired);
        create.removeAll(actual);
        Set<Grant> delete = new HashSet<>(actual);
        delete.removeAll(desired);
        return new Result(create, delete);
    }

    public record Result(Set<Grant> create, Set<Grant> delete) {
        public Result {
            create = Set.copyOf(create);
            delete = Set.copyOf(delete);
        }
    }
}
