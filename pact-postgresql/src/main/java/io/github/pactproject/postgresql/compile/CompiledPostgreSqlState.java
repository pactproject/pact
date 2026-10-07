package io.github.pactproject.postgresql.compile;

import io.github.pactproject.postgresql.model.DefaultPrivilegeGrant;
import io.github.pactproject.postgresql.model.DefaultPrivilegeOverride;
import io.github.pactproject.postgresql.model.DefaultPrivilegeScope;
import io.github.pactproject.postgresql.model.Grant;

import java.util.Set;

public record CompiledPostgreSqlState(
        Set<Grant> grants,
        Set<DefaultPrivilegeGrant> defaultPrivileges,
        Set<DefaultPrivilegeScope> defaultPrivilegeScopes,
        Set<DefaultPrivilegeOverride> defaultPrivilegeOverrides
) {
    public CompiledPostgreSqlState {
        grants = Set.copyOf(grants);
        defaultPrivileges = Set.copyOf(defaultPrivileges);
        defaultPrivilegeScopes = Set.copyOf(defaultPrivilegeScopes);
        defaultPrivilegeOverrides = Set.copyOf(defaultPrivilegeOverrides);
    }
}
