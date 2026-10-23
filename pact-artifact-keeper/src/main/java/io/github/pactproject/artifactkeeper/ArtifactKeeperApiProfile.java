package io.github.pactproject.artifactkeeper;

/** Translates logical principal types to the server's permission write contract. */
public enum ArtifactKeeperApiProfile {
    LEGACY,
    CURRENT;

    public static ArtifactKeeperApiProfile from(String value) {
        return switch (value) {
            case "legacy" -> LEGACY;
            case "current" -> CURRENT;
            default -> throw new IllegalArgumentException(
                    "Unknown Artifact Keeper api-profile: " + value
                            + "; expected legacy or current"
            );
        };
    }

    public String permissionPrincipalType(String logicalType) {
        return switch (logicalType) {
            case "user" -> "user";
            case "service_account" -> this == LEGACY ? "user" : "service_account";
            case "group", "anonymous" -> logicalType;
            default -> throw new IllegalArgumentException(
                    "Unsupported Artifact Keeper principal type: " + logicalType
            );
        };
    }
}
