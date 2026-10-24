package io.github.pactproject.artifactkeeper.model;

import io.github.pactproject.artifactkeeper.api.ArtifactKeeperApiPermission;
import io.github.pactproject.artifactkeeper.api.ArtifactKeeperApiRepository;
import io.github.pactproject.artifactkeeper.api.ArtifactKeeperApiUser;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class ArtifactKeeperState {

    private final Map<String, ArtifactKeeperApiUser> users;
    private final Map<String, ArtifactKeeperApiRepository> repositories;
    private final List<ArtifactKeeperApiPermission> permissions;

    public ArtifactKeeperState(
            Collection<ArtifactKeeperApiUser> users,
            Collection<ArtifactKeeperApiRepository> repositories,
            Collection<ArtifactKeeperApiPermission> permissions
    ) {
        this.users = indexUsers(users);
        this.repositories = indexRepositories(repositories);
        this.permissions = List.copyOf(permissions);
    }

    public ArtifactKeeperApiUser userById(String id) {
        return require(
                users.get(id),
                "Artifact Keeper user not found: " + id
        );
    }

    public ArtifactKeeperApiUser userByUsername(String username) {
        return unique(users.values().stream()
                .filter(user -> user.username().equals(username))
                .toList(), "user", username);
    }

    public java.util.Optional<ArtifactKeeperApiUser> findUserByReference(String reference) {
        var matches = users.values().stream().filter(user -> user.username().equals(reference)
                || user.id().equalsIgnoreCase(reference)).toList();
        return matches.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(unique(matches, "user", reference));
    }

    public ArtifactKeeperApiRepository repositoryById(String id) {
        return require(
                repositories.get(id),
                "Artifact Keeper repository not found: " + id
        );
    }

    public ArtifactKeeperApiRepository repositoryByName(String name) {
        return unique(repositories.values().stream()
                .filter(repository -> repository.name().equals(name) || repository.displayName().equals(name))
                .toList(), "repository", name);
    }

    public ArtifactKeeperApiRepository repositoryByKey(String key) {
        return unique(repositories.values().stream().filter(repository -> repository.name().equals(key)).toList(),
                "repository", key);
    }

    public List<ArtifactKeeperApiPermission> permissions() {
        return permissions;
    }

    public ArtifactKeeperState addUser(
            ArtifactKeeperApiUser user
    ) {
        Map<String, ArtifactKeeperApiUser> updatedUsers =
                new java.util.HashMap<>(users);

        if (updatedUsers.put(user.id(), user) != null) {
            throw new IllegalArgumentException(
                    "Artifact Keeper user already exists: " + user.id()
            );
        }

        return new ArtifactKeeperState(
                updatedUsers.values(),
                repositories.values(),
                permissions
        );
    }

    public ArtifactKeeperState addPermission(
            ArtifactKeeperApiPermission permission
    ) {
        return new ArtifactKeeperState(
                users.values(),
                repositories.values(),
                append(permissions, permission)
        );
    }

    public ArtifactKeeperState removePermission(
            String permissionId
    ) {
        return new ArtifactKeeperState(
                users.values(),
                repositories.values(),
                permissions.stream()
                        .filter(permission ->
                                !permission.id().equals(permissionId))
                        .toList()
        );
    }

    private static <T> List<T> append(
            List<T> values,
            T value
    ) {
        java.util.ArrayList<T> result =
                new java.util.ArrayList<>(values);

        result.add(value);

        return result;
    }

    private static Map<String, ArtifactKeeperApiUser> indexUsers(
            Collection<ArtifactKeeperApiUser> users
    ) {
        return users.stream().collect(
                Collectors.toUnmodifiableMap(
                        ArtifactKeeperApiUser::id,
                        Function.identity()
                )
        );
    }

    private static Map<String, ArtifactKeeperApiRepository> indexRepositories(
            Collection<ArtifactKeeperApiRepository> repositories
    ) {
        return repositories.stream().collect(
                Collectors.toUnmodifiableMap(
                        ArtifactKeeperApiRepository::id,
                        Function.identity()
                )
        );
    }

    private static <T> T require(T value, String message) {
        if (value == null) {
            throw new IllegalArgumentException(message);
        }

        return value;
    }

    private static <T> T unique(List<T> matches, String kind, String name) {
        if (matches.isEmpty()) throw new IllegalArgumentException("Artifact Keeper " + kind + " not found: " + name);
        if (matches.size() != 1) throw new IllegalArgumentException("Ambiguous Artifact Keeper " + kind + " name: " + name + "; use an ID");
        return matches.getFirst();
    }
}
