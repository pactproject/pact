package io.github.pactproject.artifactkeeper;

/** The supported Artifact Keeper API contract. Retired profiles fail during initialization. */
public enum ArtifactKeeperApiProfile {
    CURRENT;

    public static ArtifactKeeperApiProfile from(String value) {
        return switch (value) {
            case "legacy" -> throw new IllegalArgumentException(
                    "Artifact Keeper api-profile=legacy and server 1.5.1 are no longer supported; "
                            + "upgrade Artifact Keeper to 1.11.1 and remove api-profile or set it to current"
            );
            case "current" -> CURRENT;
            default -> throw new IllegalArgumentException(
                    "Unknown Artifact Keeper api-profile: " + value
                            + "; expected current"
            );
        };
    }

    public String permissionPrincipalType(String logicalType) {
        return switch (logicalType) {
            case "user" -> "user";
            case "service_account" -> "service_account";
            case "group", "anonymous" -> logicalType;
            default -> throw new IllegalArgumentException(
                    "Unsupported Artifact Keeper principal type: " + logicalType
            );
        };
    }
}
