package io.github.pactproject.postgresql.model;

public enum DefaultPrivilegeType {
    TABLES("r", "TABLES", GrantLevel.TABLE),
    SEQUENCES("S", "SEQUENCES", GrantLevel.SEQUENCE),
    ROUTINES("f", "ROUTINES", GrantLevel.FUNCTION);

    private final String catalogType;
    private final String sqlType;
    private final GrantLevel privilegeLevel;

    DefaultPrivilegeType(
            String catalogType,
            String sqlType,
            GrantLevel privilegeLevel
    ) {
        this.catalogType = catalogType;
        this.sqlType = sqlType;
        this.privilegeLevel = privilegeLevel;
    }

    public String catalogType() {
        return catalogType;
    }

    public String sqlType() {
        return sqlType;
    }

    public GrantLevel privilegeLevel() {
        return privilegeLevel;
    }

    public static DefaultPrivilegeType forLevel(GrantLevel level) {
        return switch (level) {
            case TABLE -> TABLES;
            case SEQUENCE -> SEQUENCES;
            case FUNCTION, PROCEDURE -> ROUTINES;
            default -> throw new IllegalArgumentException(
                    "PostgreSQL default privileges do not support "
                            + level.key()
            );
        };
    }
}
