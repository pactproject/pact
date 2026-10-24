package io.github.pactproject.artifactkeeper.api;

public record ArtifactKeeperApiRepository(
        String id,
        String name,
        String displayName
) {
    public ArtifactKeeperApiRepository(String id, String name) {
        this(id, name, name);
    }
}
