package io.github.pactproject.api;

import java.util.Set;

public record PactState(
        Set<Access> accesses
) {
    public PactState(Set<Access> accesses) {
        this.accesses = Set.copyOf(accesses);
    }

    public static PactState empty() {
        return new PactState(Set.of());
    }
}
