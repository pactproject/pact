package io.github.pactproject.artifactkeeper;

import io.github.pactproject.artifactkeeper.api.ArtifactKeeperApiPermission;
import io.github.pactproject.artifactkeeper.api.ArtifactKeeperApiRepository;
import io.github.pactproject.artifactkeeper.api.ArtifactKeeperApiUser;
import io.github.pactproject.artifactkeeper.api.ArtifactKeeperClient;
import io.github.pactproject.artifactkeeper.client.ArtifactKeeperClientException;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class FakeArtifactKeeperClient implements ArtifactKeeperClient {

    private final List<ArtifactKeeperApiUser> users;
    private final List<ArtifactKeeperApiRepository> repositories;
    private final List<ArtifactKeeperApiPermission> permissions;

    private int createPermissionCalls;
    private int deletePermissionCalls;
    private int createServiceAccountCalls;

    private String failOnCreatePermission;
    private String failOnDeletePermission;
    private String failOnCreateServiceAccount;

    FakeArtifactKeeperClient(
            List<ArtifactKeeperApiUser> users,
            List<ArtifactKeeperApiRepository> repositories,
            List<ArtifactKeeperApiPermission> permissions
    ) {
        this.users = new ArrayList<>(users);
        this.repositories = new ArrayList<>(repositories);
        this.permissions = new ArrayList<>(permissions);
    }

    @Override
    public List<ArtifactKeeperApiUser> getUsers() {
        return List.copyOf(users);
    }

    @Override
    public List<ArtifactKeeperApiRepository> getRepositories() {
        return List.copyOf(repositories);
    }

    @Override
    public List<ArtifactKeeperApiPermission> getPermissions() {
        return List.copyOf(permissions);
    }

    @Override
    public ArtifactKeeperApiPermission createPermission(
            String principalType,
            String principalId,
            String targetType,
            String targetId,
            Set<String> actions
    ) throws ArtifactKeeperClientException {
        createPermissionCalls++;

        if (failOnCreatePermission != null) {
            throw new ArtifactKeeperClientException(
                    "Simulated permission creation failure: "
                            + failOnCreatePermission
            );
        }

        ArtifactKeeperApiPermission permission =
                new ArtifactKeeperApiPermission(
                        UUID.randomUUID().toString(),
                        principalType,
                        principalId,
                        targetType,
                        targetId,
                        Set.copyOf(actions)
                );

        permissions.add(permission);

        return permission;
    }

    @Override
    public void deletePermission(
            String id
    ) throws ArtifactKeeperClientException {
        deletePermissionCalls++;

        if (id.equals(failOnDeletePermission)) {
            throw new ArtifactKeeperClientException(
                    "Simulated permission deletion failure: " + id
            );
        }

        permissions.removeIf(permission ->
                permission.id().equals(id)
        );
    }

    @Override
    public ArtifactKeeperApiUser createServiceAccount(
            String name
    ) throws ArtifactKeeperClientException {
        createServiceAccountCalls++;

        if (failOnCreateServiceAccount != null) {
            throw new ArtifactKeeperClientException(
                    "Simulated service account creation failure: "
                            + failOnCreateServiceAccount
            );
        }

        ArtifactKeeperApiUser user =
                new ArtifactKeeperApiUser(
                        UUID.randomUUID().toString(),
                        "svc-" + name
                );

        users.add(user);

        return user;
    }

    int createPermissionCalls() {
        return createPermissionCalls;
    }

    int deletePermissionCalls() {
        return deletePermissionCalls;
    }

    int createServiceAccountCalls() {
        return createServiceAccountCalls;
    }

    void failOnCreatePermission() {
        failOnCreatePermission = "createPermission";
    }

    void failOnDeletePermission(String id) {
        failOnDeletePermission = id;
    }

    void failOnCreateServiceAccount() {
        failOnCreateServiceAccount = "createServiceAccount";
    }
}
