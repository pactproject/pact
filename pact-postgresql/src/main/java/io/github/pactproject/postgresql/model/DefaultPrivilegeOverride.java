package io.github.pactproject.postgresql.model;

public record DefaultPrivilegeOverride(
        GrantTarget target,
        String role,
        DefaultPrivilegeType type
) {
    public DefaultPrivilegeOverride {
        if (target == null || role == null || role.isBlank() || type == null) {
            throw new IllegalArgumentException(
                    "PostgreSQL default privilege override is incomplete"
            );
        }
    }

    public boolean matches(Grant grant) {
        return role.equals(grant.role())
                && target.equals(grant.target())
                && type == DefaultPrivilegeType.forLevel(
                        grant.target().level()
                );
    }
}
