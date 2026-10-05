package io.github.pactproject.postgresql.model;

public record Grant(
        GrantTarget target,
        String role,
        Privilege privilege
) {
    public Grant {
        if (target == null) {
            throw new IllegalArgumentException("Grant target is required");
        }
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("PostgreSQL role must not be blank");
        }
        if (privilege == null) {
            throw new IllegalArgumentException("Grant privilege is required");
        }
        if (!privilege.appliesTo(target.level())) {
            throw new IllegalArgumentException(
                    "Privilege " + privilege + " does not apply to "
                            + target.level().key()
            );
        }
    }

    public String database() {
        return target.database();
    }
}
