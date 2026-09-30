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
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ArtifactKeeperCompiler {

    private static final String ACTIONS_ATTRIBUTE = "actions";

    private ArtifactKeeperCompiler() {
    }

    public static List<ArtifactKeeperPermission> compile(PactState state) {
        Map<Key, Set<String>> merged = new HashMap<>();

        for (Access access : state.accesses()) {
            Resource resource = access.resource();

            String repository = requireTarget(resource, "repository");
            Set<String> actions = extractActions(access.attributes());

            if (actions.isEmpty()) {
                continue;
            }

            Key key = new Key(repository, access.principal());

            merged
                    .computeIfAbsent(key, ignored -> new HashSet<>())
                    .addAll(actions);
        }

        return merged.entrySet().stream()
                .map(entry -> new ArtifactKeeperPermission(
                        entry.getKey().repository(),
                        entry.getKey().username(),
                        entry.getValue()
                ))
                .toList();
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
        Value value = attributes.get(ACTIONS_ATTRIBUTE);

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
            String repository,
            String username
    ) {
    }
}
