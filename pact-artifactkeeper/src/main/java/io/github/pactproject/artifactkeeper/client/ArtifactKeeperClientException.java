package io.github.pactproject.artifactkeeper.client;

public class ArtifactKeeperClientException extends Throwable {
    public ArtifactKeeperClientException(String s, Throwable e) {
        super(s, e);
    }

    public ArtifactKeeperClientException(String s) {
        super(s);
    }
}
