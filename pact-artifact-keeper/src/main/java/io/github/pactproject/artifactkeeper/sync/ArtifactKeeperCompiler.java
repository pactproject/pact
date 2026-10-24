package io.github.pactproject.artifactkeeper.sync;

import io.github.pactproject.api.Access;
import io.github.pactproject.api.PactState;
import io.github.pactproject.api.Resource;
import io.github.pactproject.api.value.SetValue;
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
            if (access.attributes().containsKey("conditions")) {
                throw new IllegalArgumentException("Artifact Keeper allowed_cidrs must be declared directly in access, without conditions");
            }
            for (String attribute : access.attributes().keySet()) {
                if (!Set.of("actions", "permissions", "principal-type", "allowed_cidrs").contains(attribute)) {
                    throw new IllegalArgumentException("Unsupported Artifact Keeper access attribute: " + attribute);
                }
            }

            Target target = compileTarget(resource);
            Set<String> actions = extractActions(access.attributes());
            Value cidrs = access.attributes().get("allowed_cidrs");
            Map<String, Value> conditions = cidrs == null ? Map.of() : Map.of("allowed_cidrs", cidrs);
            validateCidrs(cidrs);

            if (actions.isEmpty()) {
                continue;
            }

            String principal = access.principal();
            String principalType = stringAttribute(access.attributes(), "principal-type",
                    principal.startsWith("svc-") ? "service_account" : "user");
            if (!Set.of("user", "service_account", "group", "anonymous").contains(principalType)) {
                throw new IllegalArgumentException("Unsupported Artifact Keeper principal type: " + principalType);
            }
            if (principal.isBlank()) throw new IllegalArgumentException("Artifact Keeper principal must not be blank");
            if (principalType.equals("anonymous")) {
                principal = NIL_ID;
                if (!Set.of("repository", "project").contains(target.type()) || !actions.equals(Set.of("read"))) {
                    throw new IllegalArgumentException("Anonymous permissions may only grant read on repository or project targets");
                }
            }
            permissions.add(new ArtifactKeeperPermission(
                    target.type(), target.reference(), target.byId(), principalType, principal, actions, conditions,
                    target.repository(), target.version()));
        }
        return merge(permissions);
    }

    public static List<ArtifactKeeperPermission> merge(List<ArtifactKeeperPermission> permissions) {
        Map<Key, ArtifactKeeperPermission> merged = new HashMap<>();
        for (var permission : permissions) {
            Key key = new Key(permission.targetType(), permission.target(), permission.targetById(),
                    permission.principalType(), permission.principal(), permission.targetRepository(), permission.targetVersion());
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
                        actions, permission.conditions(), permission.targetRepository(), permission.targetVersion()));
            }
        }
        return List.copyOf(merged.values());
    }

    private static Target compileTarget(Resource resource) {
        var keys = resource.target().keySet();
        if (keys.equals(Set.of("name")) || keys.equals(Set.of("repository"))) {
            return new Target("repository", requireTarget(resource, keys.contains("name") ? "name" : "repository"),
                    false, null, null);
        }
        String type = requireTarget(resource, "type");
        if (keys.equals(Set.of("type", "id"))) {
            return new Target(type, uuid(requireTarget(resource, "id")), true, null, null);
        }
        if (Set.of("system", "global").contains(type)
                && (keys.equals(Set.of("type")) || keys.equals(Set.of("type", "name")))) {
            if (keys.contains("name") && !requireTarget(resource, "name").equals(type)) {
                throw new IllegalArgumentException("Artifact Keeper " + type + " target name must be '" + type + "'");
            }
            return new Target(type, NIL_ID, true, null, null);
        }
        if (type.equals("artifact")) {
            String pathField = keys.contains("path") ? "path" : "name";
            var expected = new HashSet<>(Set.of("type", "repository", pathField));
            if (keys.contains("version")) expected.add("version");
            if (!keys.equals(expected)) {
                throw new IllegalArgumentException("Named artifact target requires type, repository, path (or name), and optional version");
            }
            String path = requireTarget(resource, pathField);
            if (path.contains("\\") || path.chars().anyMatch(c -> Character.isISOControl(c))
                    || java.util.Arrays.stream(path.split("/", -1)).anyMatch(p -> p.isEmpty() || p.equals(".") || p.equals(".."))) {
                throw new IllegalArgumentException("Artifact Keeper artifact path must be a relative path without empty, '.' or '..' segments");
            }
            return new Target(type, path, false, requireTarget(resource, "repository"),
                    keys.contains("version") ? requireTarget(resource, "version") : null);
        }
        if (keys.equals(Set.of("type", "name")) && Set.of("repository", "project", "group").contains(type)) {
            return new Target(type, requireTarget(resource, "name"), false, null, null);
        }
        throw new IllegalArgumentException("Artifact Keeper target requires repository/name, type and id, or a supported named target");
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

    private static void validateCidrs(Value value) {
        if (value == null) return;
        if (!(value instanceof SetValue cidrs) || cidrs.values().isEmpty()) {
            throw new IllegalArgumentException("allowed_cidrs must be a non-empty set of CIDR strings");
        }
        for (Value item : cidrs.values()) {
            if (!(item instanceof StringValue cidr)) {
                throw new IllegalArgumentException("allowed_cidrs must contain only CIDR strings");
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
            throw new IllegalArgumentException("Invalid allowed_cidrs entry: " + cidr, e);
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
            String principalType, String principal, String repository, String version
    ) {
    }

    private record Target(String type, String reference, boolean byId, String repository, String version) { }
}
