package io.github.pactproject.artifactkeeper.api;

/** A catalog object's stable key and display name can both identify it. */
public record ArtifactKeeperApiNamedObject(String id, String key, String name) {
    public ArtifactKeeperApiNamedObject(String id, String name) {
        this(id, name, name);
    }

    public boolean matches(String reference) {
        return reference.equals(key) || reference.equals(name);
    }
}
