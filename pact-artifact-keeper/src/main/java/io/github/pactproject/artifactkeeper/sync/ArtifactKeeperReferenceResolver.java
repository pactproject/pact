package io.github.pactproject.artifactkeeper.sync;

import io.github.pactproject.artifactkeeper.ArtifactKeeperApiProfile;
import io.github.pactproject.artifactkeeper.api.ArtifactKeeperApiNamedObject;
import io.github.pactproject.artifactkeeper.api.ArtifactKeeperClient;
import io.github.pactproject.artifactkeeper.client.ArtifactKeeperClientException;
import io.github.pactproject.artifactkeeper.model.ArtifactKeeperPermission;
import io.github.pactproject.artifactkeeper.model.ArtifactKeeperState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Read-only resolution, scoped to one reconciliation; no catalog is cached across applies. */
public final class ArtifactKeeperReferenceResolver {
    private final ArtifactKeeperState state;
    private final ArtifactKeeperClient client;
    private final ArtifactKeeperApiProfile profile;
    private List<ArtifactKeeperApiNamedObject> groups;
    private List<ArtifactKeeperApiNamedObject> projects;
    private final Map<ArtifactKey, String> artifacts = new HashMap<>();

    public ArtifactKeeperReferenceResolver(ArtifactKeeperState state, ArtifactKeeperClient client,
                                           ArtifactKeeperApiProfile profile) {
        this.state = state;
        this.client = client;
        this.profile = profile;
    }

    public List<ArtifactKeeperPermission> resolve(List<ArtifactKeeperPermission> desired)
            throws ArtifactKeeperClientException {
        var resolved = new ArrayList<ArtifactKeeperPermission>();
        for (var permission : desired) {
            String target = resolveTarget(permission);
            String principal = permission.principal();
            String principalType = permission.principalType();
            if (principalType.equals("group")) {
                if (isUuid(principal)) principal = ArtifactKeeperCompiler.uuid(principal);
                else principal = named(groups(), "group", principal);
            } else if (principalType.equals("user") || principalType.equals("service_account")) {
                var existing = state.findUserByReference(principal);
                if (existing.isPresent()) {
                    var user = existing.get();
                    principal = user.username();
                    principalType = user.principalType();
                } else {
                    // Only an absent service-account NAME can be ensured. Never create from an ID.
                    if (!principal.startsWith("svc-")) {
                        throw new IllegalArgumentException("Artifact Keeper user not found: " + principal);
                    }
                    principalType = "service_account";
                }
            }
            if (profile == ArtifactKeeperApiProfile.LEGACY
                    && (!permission.conditions().isEmpty() || principalType.equals("anonymous"))) {
                throw new IllegalArgumentException("Anonymous and conditional grants require Artifact Keeper api-profile=current");
            }
            resolved.add(new ArtifactKeeperPermission(permission.targetType(), target,
                    !permission.targetType().equals("repository"), principalType, principal,
                    permission.actions(), permission.conditions()));
        }
        return ArtifactKeeperCompiler.merge(resolved);
    }

    private String resolveTarget(ArtifactKeeperPermission permission) throws ArtifactKeeperClientException {
        String target = permission.target();
        if (permission.targetType().equals("repository")) {
            return (permission.targetById() ? state.repositoryById(target) : state.repositoryByName(target)).name();
        }
        if (permission.targetById()) return target;
        return switch (permission.targetType()) {
            case "group" -> named(groups(), "group", target);
            case "project" -> named(projects(), "project", target);
            case "artifact" -> {
                if (profile == ArtifactKeeperApiProfile.LEGACY && permission.targetVersion() != null) {
                    throw new IllegalArgumentException("Artifact version selectors require Artifact Keeper api-profile=current");
                }
                var repository = state.repositoryByName(permission.targetRepository());
                var key = new ArtifactKey(repository.name(), target, permission.targetVersion());
                String id = artifacts.get(key);
                if (id == null) {
                    id = ArtifactKeeperCompiler.uuid(client.getArtifactId(key.repository(), key.path(), key.version()));
                    artifacts.put(key, id);
                }
                yield id;
            }
            default -> throw new IllegalArgumentException("Cannot resolve Artifact Keeper target type by name: " + permission.targetType());
        };
    }

    private List<ArtifactKeeperApiNamedObject> groups() throws ArtifactKeeperClientException {
        if (groups == null) groups = client.getGroups();
        return groups;
    }

    private List<ArtifactKeeperApiNamedObject> projects() throws ArtifactKeeperClientException {
        if (profile == ArtifactKeeperApiProfile.LEGACY) {
            throw new IllegalArgumentException("Project name resolution requires Artifact Keeper api-profile=current");
        }
        if (projects == null) projects = client.getProjects();
        return projects;
    }

    private static String named(List<ArtifactKeeperApiNamedObject> catalog, String kind, String name) {
        var matches = catalog.stream().filter(object -> object.matches(name)).toList();
        if (matches.isEmpty()) throw new IllegalArgumentException("Artifact Keeper " + kind + " not found: " + name);
        if (matches.size() != 1) throw new IllegalArgumentException("Ambiguous Artifact Keeper " + kind + " name: " + name + "; use an ID");
        return matches.getFirst().id();
    }

    private static boolean isUuid(String reference) {
        try {
            ArtifactKeeperCompiler.uuid(reference);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private record ArtifactKey(String repository, String path, String version) { }
}
