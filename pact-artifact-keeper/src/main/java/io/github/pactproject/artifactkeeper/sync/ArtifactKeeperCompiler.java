package io.github.pactproject.artifactkeeper.sync;

import io.github.pactproject.api.Access;
import io.github.pactproject.api.PactState;
import io.github.pactproject.api.Resource;
import io.github.pactproject.api.value.SetValue;
import io.github.pactproject.api.value.ObjectValue;
import io.github.pactproject.api.value.StringValue;
import io.github.pactproject.api.value.Value;
import io.github.pactproject.artifactkeeper.model.ArtifactKeeperPermission;

import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ArtifactKeeperCompiler {

    private static final String ACTIONS_ATTRIBUTE = "actions";
    public static final String NIL_ID = "00000000-0000-0000-0000-000000000000";

    private ArtifactKeeperCompiler() {
    }

    public static List<ArtifactKeeperPermission> compile(PactState state) {
        List<ArtifactKeeperPermission> permissions = new ArrayList<>();

        for (Access access : state.accesses()) {
            Resource resource = access.resource();

            boolean byId = resource.target().keySet().equals(Set.of("type", "id"));
            boolean byName = resource.target().keySet().equals(Set.of("name"));
            if (!byId && !byName && !resource.target().keySet().equals(Set.of("repository"))) {
                throw new IllegalArgumentException("Artifact Keeper target must contain repository/name, or type and id");
            }
            String targetType = byId ? requireTarget(resource, "type") : "repository";
            String target = byId ? uuid(requireTarget(resource, "id"))
                    : requireTarget(resource, byName ? "name" : "repository");
            Set<String> actions = extractActions(access.attributes());

            if (actions.isEmpty()) {
                continue;
            }

            String principal = access.principal();
            String principalType = stringAttribute(access.attributes(), "principal-type",
                    principal.startsWith("svc-") ? "service_account" : "user");
            if (!Set.of("user", "service_account", "group", "anonymous").contains(principalType)) {
                throw new IllegalArgumentException("Unsupported Artifact Keeper principal type: " + principalType);
            }
            if (principalType.equals("group")) {
                principal = uuid(principal);
            }
            if (principalType.equals("anonymous")) {
                principal = NIL_ID;
                if (!Set.of("repository", "project").contains(targetType) || !actions.equals(Set.of("read"))) {
                    throw new IllegalArgumentException("Anonymous permissions may only grant read on repository or project targets");
                }
            }
            Value conditionValue = access.attributes().get("conditions");
            Map<String, Value> conditions = Map.of();
            if (conditionValue != null) {
                if (!(conditionValue instanceof ObjectValue object)) {
                    throw new IllegalArgumentException("Artifact Keeper conditions must be an object");
                }
                conditions = Map.copyOf(object.values());
                validateConditions(conditions);
            }
            permissions.add(new ArtifactKeeperPermission(
                    targetType, target, byId, principalType, principal, actions, conditions));
        }
        return merge(permissions);
    }

    public static List<ArtifactKeeperPermission> merge(List<ArtifactKeeperPermission> permissions) {
        Map<Key, ArtifactKeeperPermission> merged = new HashMap<>();
        for (var permission : permissions) {
            Key key = new Key(permission.targetType(), permission.target(), permission.targetById(),
                    permission.principalType(), permission.principal());
            var previous = merged.get(key);
            if (previous == null) {
                merged.put(key, permission);
            } else {
                if (!previous.conditions().equals(permission.conditions())) {
                    throw new IllegalArgumentException("Conflicting conditions for the same Artifact Keeper grant");
                }
                Set<String> actions = new HashSet<>(previous.actions());
                actions.addAll(permission.actions());
                merged.put(key, new ArtifactKeeperPermission(permission.targetType(), permission.target(),
                        permission.targetById(), permission.principalType(), permission.principal(),
                        actions, permission.conditions()));
            }
        }
        return List.copyOf(merged.values());
    }

    public static String uuid(String value) {
        try {
            String id = UUID.fromString(value).toString();
            if (!id.equalsIgnoreCase(value)) {
                throw new IllegalArgumentException("Not a canonical UUID");
            }
            return id;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Artifact Keeper id must be a UUID: " + value, e);
        }
    }

    private static void validateConditions(Map<String, Value> conditions) {
        if (!Set.of("allowed_cidrs").containsAll(conditions.keySet())) {
            throw new IllegalArgumentException("Unsupported Artifact Keeper condition: " + conditions.keySet());
        }
        Value value = conditions.get("allowed_cidrs");
        if (value == null) return;
        if (!(value instanceof SetValue cidrs) || cidrs.values().isEmpty()) {
            throw new IllegalArgumentException("conditions.allowed_cidrs must be a non-empty set of CIDR strings");
        }
        for (Value item : cidrs.values()) {
            if (!(item instanceof StringValue cidr)) {
                throw new IllegalArgumentException("conditions.allowed_cidrs must contain only CIDR strings");
            }
            validateCidr(cidr.value());
        }
    }

    private static void validateCidr(String cidr) {
        try {
            String[] parts = cidr.split("/", -1);
            if (parts.length > 2 || !parts[0].matches("[0-9a-fA-F:.]+")) {
                throw new IllegalArgumentException();
            }
            // Only numeric literals reach InetAddress; never resolve hostnames.
            int bits;
            if (parts[0].contains(":")) {
                java.net.InetAddress.getByName(parts[0]);
                bits = 128;
            } else {
                String[] octets = parts[0].split("\\.", -1);
                if (octets.length != 4) throw new IllegalArgumentException();
                for (String octet : octets) {
                    if (!octet.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(octet) > 255) {
                        throw new IllegalArgumentException();
                    }
                }
                bits = 32;
            }
            int prefix = parts.length == 2 ? Integer.parseInt(parts[1]) : bits;
            if (prefix < 0 || prefix > bits) throw new IllegalArgumentException();
        } catch (IllegalArgumentException | java.net.UnknownHostException e) {
            throw new IllegalArgumentException("Invalid conditions.allowed_cidrs entry: " + cidr, e);
        }
    }

    private static String stringAttribute(Map<String, Value> attributes, String name, String fallback) {
        Value value = attributes.get(name);
        if (value == null) return fallback;
        if (!(value instanceof StringValue string) || string.value().isBlank()) {
            throw new IllegalArgumentException("Artifact Keeper " + name + " must be a non-empty string");
        }
        return string.value();
    }

    private static String requireTarget(
            Resource resource,
            String name
    ) {
        String value = resource.target().get(name);

        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Artifact Keeper resource target '" + name + "' is required"
            );
        }

        return value;
    }

    private static Set<String> extractActions(
            Map<String, Value> attributes
    ) {
        Value actionsValue = attributes.get(ACTIONS_ATTRIBUTE);
        Value permissionsValue = attributes.get("permissions");
        if (actionsValue != null && permissionsValue != null && !actionsValue.equals(permissionsValue)) {
            throw new IllegalArgumentException("Conflicting Artifact Keeper actions and permissions attributes");
        }
        Value value = actionsValue != null ? actionsValue : permissionsValue;

        if (value == null) {
            return Set.of();
        }

        if (!(value instanceof SetValue setValue)) {
            throw new IllegalArgumentException(
                    "'actions' attribute must be a set"
            );
        }

        Set<String> actions = new HashSet<>();

        for (Value item : setValue.values()) {
            if (!(item instanceof StringValue stringValue)) {
                throw new IllegalArgumentException(
                        "'actions' must contain only strings"
                );
            }

            actions.add(stringValue.value());
        }

        return Set.copyOf(actions);
    }

    private record Key(
            String targetType, String target, boolean targetById,
            String principalType, String principal
    ) {
    }
}
