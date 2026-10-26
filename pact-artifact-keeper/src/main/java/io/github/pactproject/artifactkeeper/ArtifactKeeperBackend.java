package io.github.pactproject.artifactkeeper;

import io.github.pactproject.api.Backend;
import io.github.pactproject.api.BackendTransaction;
import io.github.pactproject.api.PactState;
import io.github.pactproject.api.exception.BackendException;
import io.github.pactproject.api.exception.BackendOperationException;
import io.github.pactproject.api.exception.ValidationException;
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
import io.github.pactproject.artifactkeeper.sync.ArtifactKeeperReferenceResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Objects;

public final class ArtifactKeeperBackend
        implements Backend
{
    private static final Logger log =
            LoggerFactory.getLogger(ArtifactKeeperBackend.class);

    private final String id;
    private final ArtifactKeeperClient client;
    private final ArtifactKeeperApiProfile apiProfile;

    public ArtifactKeeperBackend(
            String id,
            ArtifactKeeperClient client)
    {
        this(id, client, ArtifactKeeperApiProfile.CURRENT);
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
        validateIdentities(pactState.identities());
        List<ArtifactKeeperPermission> desired =
                compileDesiredState(pactState);

        ArtifactKeeperState state = loadState();
        desired = resolveDesiredState(desired, state);

        List<ActualArtifactKeeperPermission> actual =
                resolveActualState(state);

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
        applyDiff(diff, state);
        log.info(
                "Reconciled Artifact Keeper backend '{}' ({} desired permission(s), "
                        + "{} created, {} deleted)",
                id,
                desired.size(),
                diff.create().size(),
                diff.delete().size()
        );
    }

    @Override
    public BackendTransaction prepareTransaction(PactState previous, PactState desired,
            java.util.Set<PactState> recoveryStates) throws BackendException {
        // Scope is the complete configured instance, including every recovery declaration.
        for (var recovery : recoveryStates) {
            validateIdentities(recovery.identities());
            compileDesiredState(recovery);
        }
        return prepareTransaction(previous, desired);
    }

    @Override
    public BackendTransaction prepareTransaction(PactState previous, PactState desired)
            throws BackendException {
        validateIdentities(desired.identities());
        List<ArtifactKeeperPermission> compiled = compileDesiredState(desired);
        ArtifactKeeperState actual = loadState();
        resolveDesiredState(compiled, actual);
        try {
            ArtifactKeeperResolver.resolve(actual);
        } catch (IllegalArgumentException e) {
            throw new BackendOperationException("Failed to resolve Artifact Keeper state", e);
        }
        // Apply re-reads references: preparation is read-only, not a remote lock.
        return Backend.super.prepareTransaction(previous, desired);
    }

    private List<ArtifactKeeperPermission> compileDesiredState(
            PactState pactState)
            throws ValidationException
    {
        try {
            for (var access : pactState.accesses()) {
                if (!id.equals(access.resource().backendId())) {
                    throw new IllegalArgumentException("Access backend does not match Artifact Keeper backend '" + id + "'");
                }
            }
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
            List<ArtifactKeeperPermission> desired, ArtifactKeeperState actual) throws ValidationException, BackendOperationException
    {
        try {
            return new ArtifactKeeperReferenceResolver(actual, client).resolve(desired);
        } catch (IllegalArgumentException e) {
            throw new ValidationException(e.getMessage(), e);
        } catch (ArtifactKeeperClientException e) {
            throw new BackendOperationException("Failed to resolve Artifact Keeper references", e);
        }
    }

    private List<ActualArtifactKeeperPermission> resolveActualState(ArtifactKeeperState state)
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
            ArtifactKeeperDiff.PermissionDiff diff, ArtifactKeeperState state)
            throws BackendOperationException, ValidationException
    {
        deletePermissions(diff.delete());
        // Only newly ensured users need a local cache; the permission diff is immutable.
        Map<String, ArtifactKeeperApiUser> createdUsers = new HashMap<>();
        for (ArtifactKeeperPermission permission : diff.create()) {
            createPermission(permission, state, createdUsers);
        }
    }

    private void deletePermissions(
            List<ActualArtifactKeeperPermission> permissions)
            throws BackendOperationException
    {
        for (ActualArtifactKeeperPermission permission
                : permissions) {

            try {
                log.debug("backend={} action=delete_permission result=started permission={}", id, permission);
                client.deletePermission(
                        permission.id()
                );
                log.debug("backend={} action=delete_permission result=completed permission_id={}", id, permission.id());

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

    private void createPermission(
            ArtifactKeeperPermission permission, ArtifactKeeperState state,
            Map<String, ArtifactKeeperApiUser> createdUsers)
            throws BackendOperationException, ValidationException
    {
        String principalType = permission.principalType();
        String principalId = permission.principal();
        if (principalType.equals("user") || principalType.equals("service_account")) {
            ArtifactKeeperApiUser user = findOrCreateUser(permission.principal(), state, createdUsers);
            principalId = user.id();
            principalType = user.principalType();
        }
        String targetId = permission.targetType().equals("repository")
                ? repositoryByName(permission.target(), state).id() : permission.target();

        try {
            log.debug("backend={} action=create_permission result=started principal={} principal_type={} target_type={} target={} actions={} conditions={}",
                    id, principalId, principalType, permission.targetType(), targetId, permission.actions(), permission.conditions());
            client.createPermission(
                            apiProfile.permissionPrincipalType(principalType),
                            principalId,
                            permission.targetType(),
                            targetId,
                            permission.actions(),
                            permission.conditions()
                    );
            log.debug("backend={} action=create_permission result=completed principal={} target={}", id, principalId, targetId);

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
            String username, ArtifactKeeperState state, Map<String, ArtifactKeeperApiUser> createdUsers)
            throws BackendOperationException, ValidationException
    {
        if (createdUsers.containsKey(username)) return createdUsers.get(username);
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

            var created = createServiceAccount(username);
            createdUsers.put(username, created);
            return created;
        }
    }

    private ArtifactKeeperApiUser createServiceAccount(
            String username)
            throws BackendOperationException
    {
        try {
                log.info(
                        "backend={} action=ensure_user result=started principal={}", id, username
                );
                ArtifactKeeperApiUser user =
                    client.createServiceAccount(
                            username.substring("svc-".length())
                    );

            log.info(
                    "backend={} action=ensure_user result=completed principal={} principal_id={}", id, username, user.id()
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
            String name, ArtifactKeeperState state)
            throws ValidationException
    {
        try {
            return state.repositoryByKey(name);
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
