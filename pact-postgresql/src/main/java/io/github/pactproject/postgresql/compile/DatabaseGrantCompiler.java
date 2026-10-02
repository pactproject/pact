package io.github.pactproject.postgresql.compile;

import io.github.pactproject.api.Access;
import io.github.pactproject.api.PactState;
import io.github.pactproject.api.value.ObjectValue;
import io.github.pactproject.api.value.SetValue;
import io.github.pactproject.api.value.StringValue;
import io.github.pactproject.api.value.Value;
import io.github.pactproject.postgresql.model.DatabaseGrant;
import io.github.pactproject.postgresql.model.DatabasePrivilege;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class DatabaseGrantCompiler {
    private DatabaseGrantCompiler() {
    }

    public static Set<DatabaseGrant> compile(
            PactState state,
            String backendId
    ) {
        Set<DatabaseGrant> result = new HashSet<>();
        for (Access access : state.accesses()) {
            if (!backendId.equals(access.resource().backendId())) {
                throw new IllegalArgumentException(
                        "Access backend '" + access.resource().backendId()
                                + "' does not match PostgreSQL backend '"
                                + backendId + "'"
                );
            }
            String database = databaseTarget(access);
            if (access.principal().isBlank()) {
                throw new IllegalArgumentException(
                        "PostgreSQL role name must not be blank"
                );
            }
            validateAttributes(access);
            Value permissionsValue = access.attributes().get("permissions");
            if (permissionsValue == null) {
                continue;
            }
            if (!(permissionsValue instanceof ObjectValue permissions)) {
                throw new IllegalArgumentException(
                        "PostgreSQL 'permissions' attribute must be an object"
                );
            }
            for (String resource : permissions.values().keySet()) {
                if (!"database".equals(resource)) {
                    throw new IllegalArgumentException(
                            "PostgreSQL supports only database permissions in this version"
                    );
                }
            }
            Value databasePermissions = permissions.values().get("database");
            if (databasePermissions == null) {
                continue;
            }
            if (!(databasePermissions instanceof SetValue permissionSet)) {
                throw new IllegalArgumentException(
                        "PostgreSQL database permissions must be a set"
                );
            }
            for (Value permission : permissionSet.values()) {
                if (!(permission instanceof StringValue string)) {
                    throw new IllegalArgumentException(
                            "PostgreSQL database permissions must contain strings"
                    );
                }
                result.add(new DatabaseGrant(
                        database,
                        access.principal(),
                        DatabasePrivilege.parse(string.value())
                ));
            }
        }
        return Set.copyOf(result);
    }

    public static Set<String> databases(PactState state, String backendId) {
        Set<String> result = new HashSet<>();
        for (Access access : state.accesses()) {
            if (!backendId.equals(access.resource().backendId())) {
                throw new IllegalArgumentException(
                        "Access backend '" + access.resource().backendId()
                                + "' does not match PostgreSQL backend '"
                                + backendId + "'"
                );
            }
            result.add(databaseTarget(access));
        }
        return Set.copyOf(result);
    }

    private static String databaseTarget(Access access) {
        Map<String, String> target = access.resource().target();
        if (!target.keySet().equals(Set.of("database"))) {
            throw new IllegalArgumentException(
                    "PostgreSQL target must contain only a database field"
            );
        }
        String database = target.get("database");
        if (database == null || database.isBlank()) {
            throw new IllegalArgumentException(
                    "PostgreSQL target database must not be blank"
            );
        }
        return database;
    }

    private static void validateAttributes(Access access) {
        for (String attribute : access.attributes().keySet()) {
            if (!"permissions".equals(attribute)) {
                throw new IllegalArgumentException(
                        "Unsupported PostgreSQL access field: " + attribute
                );
            }
        }
    }
}
