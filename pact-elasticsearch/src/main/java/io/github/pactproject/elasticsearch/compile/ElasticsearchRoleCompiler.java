package io.github.pactproject.elasticsearch.compile;

import io.github.pactproject.api.Access;
import io.github.pactproject.api.PactState;
import io.github.pactproject.api.Resource;
import io.github.pactproject.api.value.ObjectValue;
import io.github.pactproject.api.value.SetValue;
import io.github.pactproject.api.value.StringValue;
import io.github.pactproject.api.value.Value;
import io.github.pactproject.elasticsearch.model.ElasticsearchRole;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public final class ElasticsearchRoleCompiler {
    private static final Set<String> INDEX_PRIVILEGES = Set.of(
            "all", "auto_configure", "create", "create_doc", "create_index",
            "delete", "delete_index", "index", "maintenance", "manage",
            "manage_data_stream_lifecycle", "manage_follow_index", "manage_ilm",
            "manage_leader_index", "manage_rollup", "monitor", "read",
            "read_cross_cluster", "view_index_metadata", "write"
    );

    private ElasticsearchRoleCompiler() {
    }

    public static Map<String, ElasticsearchRole> compile(
            PactState state,
            String backendId
    ) {
        Map<String, Map<String, Set<String>>> aggregate = new TreeMap<>();
        for (Access access : state.accesses()) {
            Resource resource = access.resource();
            if (!backendId.equals(resource.backendId())) {
                continue;
            }
            if (access.principal() == null || access.principal().isBlank()) {
                throw new IllegalArgumentException(
                        "Elasticsearch principal must not be blank"
                );
            }
            if (resource.target().size() != 1
                    || resource.target().get("index") == null
                    || resource.target().get("index").isBlank()) {
                throw new IllegalArgumentException(
                        "Elasticsearch target must contain exactly one "
                                + "non-blank 'index' value"
                );
            }
            String index = resource.target().get("index");
            Set<String> privileges = privileges(access.attributes());
            aggregate.computeIfAbsent(
                    access.principal(), ignored -> new TreeMap<>()
            ).computeIfAbsent(index, ignored -> new TreeSet<>())
                    .addAll(privileges);
        }

        Map<String, ElasticsearchRole> result = new TreeMap<>();
        aggregate.forEach((principal, indices) -> {
            Map<String, List<String>> immutable = new TreeMap<>();
            indices.forEach((index, privileges) ->
                    immutable.put(index, List.copyOf(privileges)));
            result.put(
                    principal,
                    new ElasticsearchRole(
                            principal,
                            roleName(backendId, principal),
                            immutable
                    )
            );
        });
        return Collections.unmodifiableMap(result);
    }

    public static String roleName(String backendId, String principal) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    (backendId + "\u0000" + principal)
                            .getBytes(StandardCharsets.UTF_8)
            );
            return "pact-" + HexFormat.of().formatHex(digest, 0, 24);
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static Set<String> privileges(Map<String, Value> attributes) {
        if (attributes.size() != 1
                || !(attributes.get("permissions") instanceof ObjectValue permissions)
                || permissions.values().size() != 1
                || !(permissions.values().get("indices") instanceof SetValue set)) {
            throw new IllegalArgumentException(
                    "Elasticsearch access requires only "
                            + "permissions.indices as a set"
            );
        }
        Set<String> result = new TreeSet<>();
        for (Value value : set.values()) {
            if (!(value instanceof StringValue string)
                    || string.value() == null
                    || !INDEX_PRIVILEGES.contains(string.value())) {
                throw new IllegalArgumentException(
                        "Unsupported Elasticsearch index privilege: " + value
                );
            }
            result.add(string.value());
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException(
                    "Elasticsearch permissions.indices must not be empty"
            );
        }
        return result;
    }
}
