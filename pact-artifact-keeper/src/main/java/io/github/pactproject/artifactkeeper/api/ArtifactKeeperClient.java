package io.github.pactproject.artifactkeeper.api;

import io.github.pactproject.artifactkeeper.client.ArtifactKeeperClientException;

import java.util.Set;
import java.util.List;

public interface ArtifactKeeperClient {

    List<ArtifactKeeperApiUser> getUsers() throws ArtifactKeeperClientException;

    List<ArtifactKeeperApiRepository> getRepositories() throws ArtifactKeeperClientException;

    List<ArtifactKeeperApiPermission> getPermissions() throws ArtifactKeeperClientException;

    ArtifactKeeperApiPermission createPermission(
            String principalType,
            String principalId,
            String targetType,
            String targetId,
            Set<String> actions
    ) throws ArtifactKeeperClientException;

    void deletePermission(String permissionId) throws ArtifactKeeperClientException;

    ArtifactKeeperApiUser createServiceAccount(String name) throws ArtifactKeeperClientException;
}
