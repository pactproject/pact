package io.github.pactproject.artifactkeeper.api;

import io.github.pactproject.artifactkeeper.client.ArtifactKeeperClientException;

import java.util.Set;
import java.util.List;
import java.util.Map;
import io.github.pactproject.api.value.Value;

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

    default ArtifactKeeperApiPermission createPermission(
            String principalType, String principalId, String targetType, String targetId,
            Set<String> actions, Map<String, Value> conditions) throws ArtifactKeeperClientException {
        if (!conditions.isEmpty()) {
            throw new ArtifactKeeperClientException("Client does not support conditional permissions");
        }
        return createPermission(principalType, principalId, targetType, targetId, actions);
    }

    ArtifactKeeperApiUser createServiceAccount(String name) throws ArtifactKeeperClientException;
}
