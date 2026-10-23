package io.github.pactproject.artifactkeeper.api;

public record ArtifactKeeperApiUser(
        String id,
        String username,
        boolean serviceAccount
) {
    public ArtifactKeeperApiUser(String id, String username) {
        this(id, username, false);
    }

    public String principalType() {
        return serviceAccount ? "service_account" : "user";
    }
}
