package io.github.pactproject.artifactkeeper;

import io.github.pactproject.api.Backend;
import io.github.pactproject.api.PactState;
import io.github.pactproject.api.exception.BackendException;
import io.github.pactproject.api.exception.BackendOperationException;
import io.github.pactproject.api.exception.ValidationException;
import io.github.pactproject.artifactkeeper.api.ArtifactKeeperApiPermission;
import io.github.pactproject.artifactkeeper.api.ArtifactKeeperApiRepository;
import io.github.pactproject.artifactkeeper.api.ArtifactKeeperApiUser;
import io.github.pactproject.artifactkeeper.api.ArtifactKeeperClient;
import io.github.pactproject.artifactkeeper.client.ArtifactKeeperClientException;
import io.github.pactproject.artifactkeeper.model.ActualArtifactKeeperPermission;
import io.github.pactproject.artifactkeeper.model.ArtifactKeeperPermission;
import io.github.pactproject.artifactkeeper.model.ArtifactKeeperState;
import io.github.pactproject.artifactkeeper.sync.ArtifactKeeperCompiler;
import io.github.pactproject.artifactkeeper.sync.ArtifactKeeperDiff;
import io.github.pactproject.artifactkeeper.sync.ArtifactKeeperResolver;

import java.util.List;

public final class ArtifactKeeperBackend
        implements Backend
{
    private final String id;
    private final ArtifactKeeperClient client;

    private ArtifactKeeperState state;

    public ArtifactKeeperBackend(
            String id,
            ArtifactKeeperClient client)
    {
        this.id = id;
        this.client = client;
    }

    @Override
    public String id()
    {
        return id;
    }

    @Override
    public void apply(PactState pactState)
            throws BackendException
    {
        List<ArtifactKeeperPermission> desired =
                compileDesiredState(pactState);

        state = loadState();

        List<ActualArtifactKeeperPermission> actual =
                resolveActualState();

        ArtifactKeeperDiff.PermissionDiff diff =
                ArtifactKeeperDiff.diff(
                        actual,
                        desired
                );

        applyDiff(diff);
    }

    private List<ArtifactKeeperPermission> compileDesiredState(
            PactState pactState)
            throws ValidationException
    {
        try {
            return ArtifactKeeperCompiler.compile(
                    pactState
            );
        }
        catch (IllegalArgumentException e) {
            throw new ValidationException(
                    "Invalid Artifact Keeper configuration",
                    e
            );
        }
    }

    private ArtifactKeeperState loadState()
            throws BackendOperationException
    {
        try {
            return new ArtifactKeeperState(
                    client.getUsers(),
                    client.getRepositories(),
                    client.getPermissions()
            );
        }
        catch (ArtifactKeeperClientException e) {
            throw new BackendOperationException(
                    "Failed to load Artifact Keeper state",
                    e
            );
        }
    }

    private List<ActualArtifactKeeperPermission> resolveActualState()
            throws BackendOperationException
    {
        try {
            return ArtifactKeeperResolver.resolve(
                    state
            );
        }
        catch (IllegalArgumentException e) {
            throw new BackendOperationException(
                    "Failed to resolve Artifact Keeper state",
                    e
            );
        }
    }

    private void applyDiff(
            ArtifactKeeperDiff.PermissionDiff diff)
            throws BackendOperationException, ValidationException
    {
        deletePermissions(diff.delete());
        createPermissions(diff.create());
    }

    private void deletePermissions(
            List<ActualArtifactKeeperPermission> permissions)
            throws BackendOperationException
    {
        for (ActualArtifactKeeperPermission permission
                : permissions) {

            try {
                client.deletePermission(
                        permission.id()
                );

                state = state.removePermission(
                        permission.id()
                );
            }
            catch (ArtifactKeeperClientException e) {
                throw new BackendOperationException(
                        "Failed to delete Artifact Keeper "
                                + "permission: "
                                + permission.id(),
                        e
                );
            }
        }
    }

    private void createPermissions(
            List<ArtifactKeeperPermission> permissions)
            throws BackendOperationException, ValidationException
    {
        for (ArtifactKeeperPermission permission
                : permissions) {

            createPermission(permission);
        }
    }

    private void createPermission(
            ArtifactKeeperPermission permission)
            throws BackendOperationException, ValidationException
    {
        ArtifactKeeperApiUser user =
                findOrCreateUser(
                        permission.username()
                );

        ArtifactKeeperApiRepository repository =
                repositoryByName(
                        permission.repository()
                );

        try {
            ArtifactKeeperApiPermission created =
                    client.createPermission(
                            "user",
                            user.id(),
                            "repository",
                            repository.id(),
                            permission.actions()
                    );

            state = state.addPermission(created);
        }
        catch (ArtifactKeeperClientException e) {
            throw new BackendOperationException(
                    "Failed to create Artifact Keeper "
                            + "permission for "
                            + permission.username()
                            + " on "
                            + permission.repository(),
                    e
            );
        }
    }

    private ArtifactKeeperApiUser findOrCreateUser(
            String username)
            throws BackendOperationException, ValidationException
    {
        try {
            return state.userByUsername(username);
        }
        catch (IllegalArgumentException e) {
            if (!username.startsWith("svc-")) {
                throw new ValidationException(
                        "Artifact Keeper user not found: "
                                + username,
                        e
                );
            }

            return createServiceAccount(username);
        }
    }

    private ArtifactKeeperApiUser createServiceAccount(
            String username)
            throws BackendOperationException
    {
        try {
            ArtifactKeeperApiUser user =
                    client.createServiceAccount(
                            username.substring("svc-".length())
                    );

            state = state.addUser(user);

            return user;
        }
        catch (ArtifactKeeperClientException e) {
            throw new BackendOperationException(
                    "Failed to create Artifact Keeper "
                            + "service account: "
                            + username,
                    e
            );
        }
    }

    private ArtifactKeeperApiRepository repositoryByName(
            String name)
            throws ValidationException
    {
        try {
            return state.repositoryByName(name);
        }
        catch (IllegalArgumentException e) {
            throw new ValidationException(
                    "Artifact Keeper repository not found: "
                            + name,
                    e
            );
        }
    }
}
