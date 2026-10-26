package io.github.pactproject.artifactkeeper.client;

public class ArtifactKeeperClientException extends Exception {
    public ArtifactKeeperClientException(String s, Throwable e) {
        super(s, e);
    }

    public ArtifactKeeperClientException(String s) {
        super(s);
    }
}
