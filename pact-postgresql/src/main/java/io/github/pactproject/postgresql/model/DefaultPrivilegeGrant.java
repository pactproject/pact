package io.github.pactproject.postgresql.model;

public record DefaultPrivilegeGrant(
        DefaultPrivilegeScope scope,
        String role,
        Privilege privilege
) {
    public DefaultPrivilegeGrant {
        if (scope == null) {
            throw new IllegalArgumentException(
                    "PostgreSQL default privilege scope is required"
            );
        }
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException(
                    "PostgreSQL default privilege grantee must not be blank"
            );
        }
        if (privilege == null
                || !privilege.appliesTo(scope.type().privilegeLevel())) {
            throw new IllegalArgumentException(
                    "Privilege does not apply to PostgreSQL default object type "
                            + scope.type()
            );
        }
    }
}
