package io.github.pactproject.kubernetes.compile;

import io.github.pactproject.api.Access;
import io.github.pactproject.api.PactState;
import io.github.pactproject.api.Resource;
import io.github.pactproject.api.value.Value;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class DataAccessCompiler
{
    private static final String ACCESS_FIELD = "access";
    private static final String USERS_FIELD = "users";
    private static final String REGISTRY_SERVICE = "registry";
    private static final Set<String> REGISTRY_PERMISSIONS =
            Set.of("read", "write", "delete", "admin");

    private final ValueParser valueParser;

    public DataAccessCompiler()
    {
        this.valueParser = new ValueParser();
    }

    public PactState compile(
            Iterable<? extends Map<String, ?>> dataAccessSpecs)
    {
        Set<Access> accesses = new HashSet<>();

        for (Map<String, ?> spec : dataAccessSpecs) {
            accesses.addAll(
                    compileDataAccess(spec)
            );
        }

        return new PactState(accesses);
    }

    private Set<Access> compileDataAccess(
            Map<String, ?> spec)
    {
        Object resourcesValue = spec.get("resources");

        if (!(resourcesValue instanceof Iterable<?> resources)) {
            throw invalid(
                    "DataAccess spec.resources must be an array"
            );
        }

        Set<Access> accesses = new HashSet<>();

        for (Object resourceValue : resources) {
            if (!(resourceValue instanceof Map<?, ?> resource)) {
                throw invalid(
                        "DataAccess resource must be an object"
                );
            }

            accesses.addAll(
                    compileResource(resource)
            );
        }

        return accesses;
    }

    private Set<Access> compileResource(
            Map<?, ?> resource)
    {
        Object accessValue = resource.get(ACCESS_FIELD);

        if (!(accessValue instanceof Iterable<?> accessItems)) {
            throw invalid(
                    "DataAccess resource.access must be an array"
            );
        }

        String backendId = findBackendId(resource);

        Map<String, String> target;
        if (backendId.equals(REGISTRY_SERVICE)) {
            Object targetValue = resource.get(backendId);
            if (!(targetValue instanceof Map<?, ?> registryTarget)
                    || !registryTarget.keySet().equals(Set.of("name"))
                    || !(registryTarget.get("name") instanceof String name)
                    || name.isBlank()) {
                throw invalid(
                        "Registry service requires only a non-empty name field"
                );
            }
            target = Map.of("repository", name);
        }
        else {
            target = parseTarget(resource.get(backendId));
        }

        Resource pactResource = new Resource(backendId, target);

        Set<Access> result = new HashSet<>();

        for (Object accessItem : accessItems) {
            if (!(accessItem instanceof Map<?, ?> access)) {
                throw invalid(
                        "DataAccess access item must be an object"
                );
            }

            result.addAll(
                    compileAccess(
                            pactResource,
                            access
                    )
            );
        }

        return result;
    }

    private String findBackendId(Map<?, ?> resource)
    {
        String backendId = null;

        for (Object keyObject : resource.keySet()) {
            if (!(keyObject instanceof String key)) {
                throw invalid(
                        "DataAccess resource field name must be a string"
                );
            }

            if (key.equals(ACCESS_FIELD)) {
                continue;
            }

            if (backendId != null) {
                throw invalid(
                        "DataAccess resource must contain exactly one "
                                + "service field"
                );
            }

            backendId = key;
        }

        if (backendId == null) {
            throw invalid(
                    "DataAccess resource must contain exactly one "
                            + "service field"
            );
        }

        return backendId;
    }

    private Set<Access> compileAccess(
            Resource resource,
            Map<?, ?> access)
    {
        if (resource.backendId().equals(REGISTRY_SERVICE)) {
            return compileRegistryAccess(resource, access);
        }

        Object usersValue = access.get(USERS_FIELD);
        if (access.containsKey(USERS_FIELD)
                && !(usersValue instanceof Iterable<?>)) {
            throw invalid(
                    "DataAccess access.users must be an array"
            );
        }
        Iterable<?> users =
                usersValue instanceof Iterable<?> iterable
                        ? iterable
                        : List.of();

        Map<String, Value> attributes = new HashMap<>();

        for (Map.Entry<?, ?> entry : access.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw invalid(
                        "DataAccess access field name must be a string"
                );
            }

            if (!key.equals(USERS_FIELD)) {
                attributes.put(
                        key,
                        valueParser.parse(entry.getValue())
                );
            }
        }

        if (!access.containsKey(USERS_FIELD)) {
            return Set.of();
        }
        Set<Access> result = new HashSet<>();

        for (Object userValue : users) {
            if (!(userValue instanceof String username)) {
                throw invalid(
                        "DataAccess access.users must contain only strings"
                );
            }

            result.add(
                    new Access(
                            username,
                            resource,
                            attributes
                    )
            );
        }

        return result;
    }

    private Set<Access> compileRegistryAccess(
            Resource resource,
            Map<?, ?> access)
    {
        for (Object keyObject : access.keySet()) {
            if (!(keyObject instanceof String key)) {
                throw invalid(
                        "DataAccess access field name must be a string"
                );
            }

            if (!key.equals(USERS_FIELD)
                    && !key.equals("permissions")) {
                throw invalid(
                        "Registry access does not support field '"
                                + key
                                + "'"
                );
            }
        }

        Object usersValue = access.get(USERS_FIELD);
        if (access.containsKey(USERS_FIELD)
                && !(usersValue instanceof Iterable<?>)) {
            throw invalid(
                    "DataAccess access.users must be an array"
            );
        }
        List<String> usernames = new ArrayList<>();
        if (usersValue instanceof Iterable<?> users) {
            for (Object userValue : users) {
                if (!(userValue instanceof String username)) {
                    throw invalid(
                            "DataAccess access.users must contain only strings"
                    );
                }
                usernames.add(username);
            }
        }

        Object permissionValue = access.get("permissions");
        if (access.containsKey("permissions")
                && !(permissionValue instanceof Iterable<?>)) {
            throw invalid(
                    "Registry access.permissions must be an array"
            );
        }
        Set<String> permissions =
                parseRegistryPermissions(permissionValue);
        if (!access.containsKey(USERS_FIELD)
                || permissions.isEmpty()
                || usernames.isEmpty()) {
            return Set.of();
        }
        Set<Value> actionValues = new HashSet<>();
        permissions.forEach(permission ->
                actionValues.add(Value.string(permission))
        );
        Map<String, Value> attributes = Map.of(
                "actions",
                Value.set(actionValues)
        );

        Set<Access> result = new HashSet<>();
        for (String username : usernames) {
            result.add(new Access(username, resource, attributes));
        }
        return result;
    }

    private Set<String> parseRegistryPermissions(Object value)
    {
        if (value == null) {
            return Set.of();
        }
        if (!(value instanceof Iterable<?> permissions)) {
            throw invalid(
                    "Registry access.permissions must be an array"
            );
        }

        Set<String> result = new HashSet<>();
        for (Object permission : permissions) {
            if (!(permission instanceof String name)
                    || !REGISTRY_PERMISSIONS.contains(name)) {
                throw invalid(
                        "Unsupported Registry permission: "
                                + permission
                );
            }
            result.add(name);
        }
        return Set.copyOf(result);
    }

    private Map<String, String> parseTarget(
            Object targetValue)
    {
        if (!(targetValue instanceof Map<?, ?> target)) {
            throw invalid(
                    "DataAccess service field must be an object"
            );
        }

        Map<String, String> result = new HashMap<>();

        for (Map.Entry<?, ?> entry : target.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw invalid(
                        "DataAccess target field name must be a string"
                );
            }

            Object value = entry.getValue();

            if (value instanceof String string) {
                result.put(key, string);
            }
            else if (value instanceof Boolean bool) {
                result.put(key, Boolean.toString(bool));
            }
            else if (value instanceof Number number) {
                result.put(key, number.toString());
            }
            else {
                throw invalid(
                        "DataAccess target field '"
                                + key
                                + "' must be a scalar"
                );
            }
        }

        return result;
    }

    private static IllegalArgumentException invalid(
            String message)
    {
        return new IllegalArgumentException(message);
    }
}
