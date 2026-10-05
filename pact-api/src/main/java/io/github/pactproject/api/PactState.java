package io.github.pactproject.api;

import java.util.Set;
import java.util.HashMap;
import java.util.Map;

public record PactState(
        Set<Access> accesses,
        Set<Identity> identities
) {
    public PactState(Set<Access> accesses) {
        this(accesses, Set.of());
    }

    public PactState(Set<Access> accesses, Set<Identity> identities) {
        this.accesses = Set.copyOf(accesses);
        Map<String, Identity> byPrincipal = new HashMap<>();
        for (Identity identity : identities) {
            String key = identity.backendId() + "\u0000" + identity.principal();
            Identity previous = byPrincipal.putIfAbsent(key, identity);
            if (previous != null && !previous.equals(identity)) {
                throw new IllegalArgumentException(
                        "Conflicting identity declarations for backend '"
                                + identity.backendId() + "' and principal '"
                                + identity.principal() + "'"
                );
            }
        }
        this.identities = Set.copyOf(byPrincipal.values());
    }

    public static PactState empty() {
        return new PactState(Set.of(), Set.of());
    }

    public PactState withoutSecrets() {
        return new PactState(
                accesses,
                identities.stream()
                        .map(Identity::withoutPassword)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet())
        );
    }
}
