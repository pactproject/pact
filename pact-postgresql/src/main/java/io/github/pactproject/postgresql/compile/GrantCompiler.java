package io.github.pactproject.postgresql.compile;

import io.github.pactproject.api.Access;
import io.github.pactproject.api.PactState;
import io.github.pactproject.api.value.ObjectValue;
import io.github.pactproject.api.value.SetValue;
import io.github.pactproject.api.value.StringValue;
import io.github.pactproject.api.value.Value;
import io.github.pactproject.postgresql.model.DefaultPrivilegeGrant;
import io.github.pactproject.postgresql.model.DefaultPrivilegeOverride;
import io.github.pactproject.postgresql.model.DefaultPrivilegeScope;
import io.github.pactproject.postgresql.model.DefaultPrivilegeType;
import io.github.pactproject.postgresql.model.Grant;
import io.github.pactproject.postgresql.model.GrantLevel;
import io.github.pactproject.postgresql.model.GrantTarget;
import io.github.pactproject.postgresql.model.Privilege;
import io.github.pactproject.postgresql.model.RoutineSignature;

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
        return compileState(state, backendId).grants();
    }

    public static CompiledPostgreSqlState compileState(
            PactState state,
            String backendId
    ) {
        Set<Grant> grants = new HashSet<>();
        Set<DefaultPrivilegeGrant> defaultPrivileges = new HashSet<>();
        Set<DefaultPrivilegeScope> defaultScopes = new HashSet<>();
        Set<DefaultPrivilegeOverride> overrides = new HashSet<>();

        for (Access access : state.accesses()) {
            GrantTarget target = target(access, backendId);
            if (access.principal().isBlank()) {
                throw new IllegalArgumentException(
                        "PostgreSQL role name must not be blank"
                );
            }
            for (String attribute : access.attributes().keySet()) {
                if (!"permissions".equals(attribute)
                        && !"defaultPrivileges".equals(attribute)) {
                    throw new IllegalArgumentException(
                            "Unsupported PostgreSQL access field: " + attribute
                    );
                }
            }

            Value permissionsValue = access.attributes().get("permissions");
            if (permissionsValue != null) {
                ObjectValue permissions = objectValue(
                        permissionsValue, "permissions"
                );
                for (Map.Entry<String, ? extends Value> entry
                        : permissions.values().entrySet()) {
                    GrantLevel level = GrantLevel.fromKey(entry.getKey())
                            .orElseThrow(() -> new IllegalArgumentException(
                                    "Unknown PostgreSQL permission resource: "
                                            + entry.getKey()
                            ));
                    GrantTarget levelTarget = target.pathTo(level);
                    for (String name : privilegeNames(entry.getValue(), level)) {
                        grants.add(new Grant(
                                levelTarget,
                                access.principal(),
                                Privilege.parse(level, name)
                        ));
                    }
                    if (isDefaultObjectLevel(level)) {
                        overrides.add(new DefaultPrivilegeOverride(
                                levelTarget,
                                access.principal(),
                                DefaultPrivilegeType.forLevel(level)
                        ));
                    }
                }
            }

            Value defaultValue = access.attributes().get("defaultPrivileges");
            if (defaultValue != null) {
                if (target.schema() == null
                        || target.table() != null
                        || target.column() != null
                        || target.sequence() != null
                        || target.function() != null
                        || target.procedure() != null) {
                    throw new IllegalArgumentException(
                            "PostgreSQL defaultPrivileges require a "
                                    + "database-and-schema target"
                    );
                }
                compileDefaultPrivileges(
                        defaultValue,
                        target,
                        access.principal(),
                        defaultPrivileges,
                        defaultScopes
                );
            }
        }

        overrides.removeIf(override -> defaultScopes.stream().noneMatch(
                scope -> scope.database().equals(
                                override.target().database())
                        && scope.schema().equals(override.target().schema())
                        && scope.type() == override.type()
                        && defaultPrivileges.stream().anyMatch(grant ->
                                grant.scope().equals(scope)
                                        && grant.role().equals(override.role()))
        ));
        return new CompiledPostgreSqlState(
                grants,
                defaultPrivileges,
                defaultScopes,
                overrides
        );
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

    private static void compileDefaultPrivileges(
            Value value,
            GrantTarget target,
            String grantee,
            Set<DefaultPrivilegeGrant> grants,
            Set<DefaultPrivilegeScope> scopes
    ) {
        ObjectValue defaults = objectValue(value, "defaultPrivileges");
        for (String key : defaults.values().keySet()) {
            if (!"creator".equals(key) && !"permissions".equals(key)) {
                throw new IllegalArgumentException(
                        "Unsupported PostgreSQL defaultPrivileges field: "
                                + key
                );
            }
        }
        Value creatorValue = defaults.values().get("creator");
        if (!(creatorValue instanceof StringValue creator)
                || creator.value().isBlank()) {
            throw new IllegalArgumentException(
                    "PostgreSQL defaultPrivileges.creator must be a "
                            + "non-blank string"
            );
        }
        Value permissionsValue = defaults.values().get("permissions");
        if (permissionsValue == null) {
            throw new IllegalArgumentException(
                    "PostgreSQL defaultPrivileges.permissions is required"
            );
        }
        ObjectValue permissions = objectValue(
                permissionsValue, "defaultPrivileges.permissions"
        );
        for (Map.Entry<String, ? extends Value> entry
                : permissions.values().entrySet()) {
            GrantLevel level = GrantLevel.fromKey(entry.getKey())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Unknown PostgreSQL default permission resource: "
                                    + entry.getKey()
                    ));
            DefaultPrivilegeType type = DefaultPrivilegeType.forLevel(level);
            DefaultPrivilegeScope scope = new DefaultPrivilegeScope(
                    target.database(),
                    target.schema(),
                    creator.value(),
                    type
            );
            scopes.add(scope);
            for (String name : privilegeNames(entry.getValue(), level)) {
                grants.add(new DefaultPrivilegeGrant(
                        scope,
                        grantee,
                        Privilege.parse(level, name)
                ));
            }
        }
    }

    private static ObjectValue objectValue(Value value, String field) {
        if (!(value instanceof ObjectValue object)) {
            throw new IllegalArgumentException(
                    "PostgreSQL '" + field + "' attribute must be an object"
            );
        }
        return object;
    }

    private static boolean isDefaultObjectLevel(GrantLevel level) {
        return level == GrantLevel.TABLE
                || level == GrantLevel.SEQUENCE
                || level == GrantLevel.FUNCTION
                || level == GrantLevel.PROCEDURE;
    }
}
