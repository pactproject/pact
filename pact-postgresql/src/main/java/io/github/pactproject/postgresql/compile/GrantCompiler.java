package io.github.pactproject.postgresql.compile;

import io.github.pactproject.api.Access;
import io.github.pactproject.api.PactState;
import io.github.pactproject.api.value.ObjectValue;
import io.github.pactproject.api.value.SetValue;
import io.github.pactproject.api.value.StringValue;
import io.github.pactproject.api.value.Value;
import io.github.pactproject.postgresql.model.Grant;
import io.github.pactproject.postgresql.model.GrantLevel;
import io.github.pactproject.postgresql.model.GrantTarget;
import io.github.pactproject.postgresql.model.RoutineSignature;
import io.github.pactproject.postgresql.model.Privilege;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class GrantCompiler {
    private static final Set<String> TARGET_FIELDS =
            Set.of("database", "schema", "table", "column", "sequence",
                    "function", "procedure");

    private GrantCompiler() {
    }

    public static Set<Grant> compile(PactState state, String backendId) {
        Set<Grant> result = new HashSet<>();
        for (Access access : state.accesses()) {
            GrantTarget target = target(access, backendId);
            if (access.principal().isBlank()) {
                throw new IllegalArgumentException(
                        "PostgreSQL role name must not be blank"
                );
            }
            for (String attribute : access.attributes().keySet()) {
                if (!"permissions".equals(attribute)) {
                    throw new IllegalArgumentException(
                            "Unsupported PostgreSQL access field: " + attribute
                    );
                }
            }
            Value permissionsValue = access.attributes().get("permissions");
            if (permissionsValue == null) {
                continue;
            }
            if (!(permissionsValue instanceof ObjectValue permissions)) {
                throw new IllegalArgumentException(
                        "PostgreSQL 'permissions' attribute must be an object"
                );
            }
            for (Map.Entry<String, ? extends Value> entry
                    : permissions.values().entrySet()) {
                GrantLevel level = GrantLevel.fromKey(entry.getKey())
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Unknown PostgreSQL permission resource: "
                                        + entry.getKey()
                        ));
                GrantTarget levelTarget = target.pathTo(level);
                for (String name : privilegeNames(entry.getValue(), level)) {
                    result.add(new Grant(
                            levelTarget,
                            access.principal(),
                            Privilege.parse(level, name)
                    ));
                }
            }
        }
        return Set.copyOf(result);
    }

    public static Set<String> databases(PactState state, String backendId) {
        Set<String> result = new HashSet<>();
        for (Access access : state.accesses()) {
            result.add(target(access, backendId).database());
        }
        return Set.copyOf(result);
    }

    private static GrantTarget target(Access access, String backendId) {
        if (!backendId.equals(access.resource().backendId())) {
            throw new IllegalArgumentException(
                    "Access backend '" + access.resource().backendId()
                            + "' does not match PostgreSQL backend '"
                            + backendId + "'"
                );
        }
        Map<String, String> fields = access.resource().target();
        for (String field : fields.keySet()) {
            if (!TARGET_FIELDS.contains(field)) {
                throw new IllegalArgumentException(
                        "Unknown PostgreSQL resource target field: " + field
                );
            }
        }
        return new GrantTarget(
                fields.get("database"),
                fields.get("schema"),
                fields.get("table"),
                fields.get("column"),
                fields.get("sequence"),
                fields.containsKey("function")
                        ? RoutineSignature.parse(fields.get("function"))
                        : null,
                fields.containsKey("procedure")
                        ? RoutineSignature.parse(fields.get("procedure"))
                        : null
        );
    }

    private static Set<String> privilegeNames(Value value, GrantLevel level) {
        if (!(value instanceof SetValue set)) {
            throw new IllegalArgumentException(
                    "PostgreSQL " + level.key() + " permissions must be a set"
            );
        }
        Set<String> names = new HashSet<>();
        for (Value item : set.values()) {
            if (!(item instanceof StringValue string)) {
                throw new IllegalArgumentException(
                        "PostgreSQL " + level.key()
                                + " permissions must contain strings"
                );
            }
            names.add(string.value());
        }
        return names;
    }
}
