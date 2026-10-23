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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class ArtifactKeeperBackend
        implements Backend
{
    private static final Logger log =
            LoggerFactory.getLogger(ArtifactKeeperBackend.class);

    private final String id;
    private final ArtifactKeeperClient client;
    private final ArtifactKeeperApiProfile apiProfile;

    private ArtifactKeeperState state;

    public ArtifactKeeperBackend(
            String id,
            ArtifactKeeperClient client)
    {
        this(id, client, ArtifactKeeperApiProfile.LEGACY);
    }

    public ArtifactKeeperBackend(
            String id,
            ArtifactKeeperClient client,
            ArtifactKeeperApiProfile apiProfile)
    {
        this.id = id;
        this.client = client;
        this.apiProfile = Objects.requireNonNull(apiProfile);
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
        desired = resolveDesiredState(desired);

        List<ActualArtifactKeeperPermission> actual =
                resolveActualState();

        ArtifactKeeperDiff.PermissionDiff diff =
                ArtifactKeeperDiff.diff(
                        actual,
                        desired
                );

        log.debug(
                "Artifact Keeper backend '{}' permission diff: {} create, {} delete",
                id,
                diff.create().size(),
                diff.delete().size()
        );
        applyDiff(diff);
        log.info(
                "Reconciled Artifact Keeper backend '{}' ({} desired permission(s), "
                        + "{} created, {} deleted)",
                id,
                desired.size(),
                diff.create().size(),
                diff.delete().size()
        );
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

    private List<ArtifactKeeperPermission> resolveDesiredState(
            List<ArtifactKeeperPermission> desired) throws ValidationException
    {
        var resolved = new ArrayList<ArtifactKeeperPermission>();
        for (ArtifactKeeperPermission permission : desired) {
            String target = permission.target();
            boolean targetById = permission.targetById();
            if (permission.targetType().equals("repository")) {
                try {
                    target = targetById ? state.repositoryById(target).name() : repositoryByName(target).name();
                } catch (IllegalArgumentException e) {
                    throw new ValidationException(e.getMessage(), e);
                }
                targetById = false;
            }
            String principalType = permission.principalType();
            if (principalType.equals("user") || principalType.equals("service_account")) {
                try {
                    principalType = state.userByUsername(permission.principal()).principalType();
                }
                catch (IllegalArgumentException e) {
                    if (!permission.principal().startsWith("svc-")) {
                        throw new ValidationException("Artifact Keeper user not found: " + permission.principal(), e);
                    }
                    principalType = "service_account";
                }
            }
            if (apiProfile == ArtifactKeeperApiProfile.LEGACY
                    && (!permission.conditions().isEmpty() || principalType.equals("anonymous"))) {
                throw new ValidationException("Anonymous and conditional grants require Artifact Keeper api-profile=current");
            }
            resolved.add(new ArtifactKeeperPermission(
                    permission.targetType(), target, targetById, principalType, permission.principal(),
                    permission.actions(), permission.conditions()
            ));
        }
        try {
            return ArtifactKeeperCompiler.merge(resolved);
        } catch (IllegalArgumentException e) {
            throw new ValidationException(e.getMessage(), e);
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
        String principalType = permission.principalType();
        String principalId = permission.principal();
        if (principalType.equals("user") || principalType.equals("service_account")) {
            ArtifactKeeperApiUser user = findOrCreateUser(permission.principal());
            principalId = user.id();
            principalType = user.principalType();
        }
        String targetId = permission.targetType().equals("repository")
                ? repositoryByName(permission.target()).id() : permission.target();

        try {
            ArtifactKeeperApiPermission created =
                    client.createPermission(
                            apiProfile.permissionPrincipalType(principalType),
                            principalId,
                            permission.targetType(),
                            targetId,
                            permission.actions(),
                            permission.conditions()
                    );

            state = state.addPermission(created);
        }
        catch (ArtifactKeeperClientException e) {
            throw new BackendOperationException(
                    "Failed to create Artifact Keeper "
                            + "permission for "
                            + permission.username()
                            + " on "
                            + permission.repository()
                            + " (api-profile=" + apiProfile.name().toLowerCase(java.util.Locale.ROOT) + ")",
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
                log.info(
                        "Creating Artifact Keeper service account for backend '{}'",
                        id
                );
                ArtifactKeeperApiUser user =
                    client.createServiceAccount(
                            username.substring("svc-".length())
                    );

            state = state.addUser(user);
            log.info(
                    "Created Artifact Keeper service account for backend '{}'",
                    id
            );

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
