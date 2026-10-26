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
    private ElasticsearchRoleCompiler() { }

    public static Map<String, ElasticsearchRole> compile(PactState state, String backendId) {
        Map<String, MutableAccess> aggregate = new TreeMap<>();
        for (Access access : state.accesses()) {
            Resource resource = access.resource();
            if (!backendId.equals(resource.backendId())) {
                throw new IllegalArgumentException("Elasticsearch access backendId must match configured backend: " + backendId);
            }
            if (access.principal() == null || access.principal().isBlank()) {
                throw new IllegalArgumentException("Elasticsearch principal must not be blank");
            }
            boolean cluster = resource.target().equals(Map.of("cluster", "*"));
            String index = resource.target().get("index");
            if (!cluster && (resource.target().size() != 1 || index == null || index.isBlank())) {
                throw new IllegalArgumentException("Elasticsearch target must contain exactly one non-blank index or cluster: '*'");
            }
            var attributes = access.attributes();
            if (attributes.isEmpty() || !Set.of("permissions", "roles").containsAll(attributes.keySet())) {
                throw new IllegalArgumentException("Elasticsearch access accepts permissions and optional roles only");
            }
            var entry = aggregate.computeIfAbsent(access.principal(), ignored -> new MutableAccess());
            if (attributes.containsKey("roles")) {
                if (!cluster) throw new IllegalArgumentException("Elasticsearch roles must be declared on cluster: '*'");
                entry.roles.addAll(strings(attributes.get("roles"), "roles"));
            }
            if (attributes.containsKey("permissions")) {
                String kind = cluster ? "cluster" : "indices";
                if (!(attributes.get("permissions") instanceof ObjectValue permissions)
                        || !permissions.values().keySet().equals(Set.of(kind))) {
                    throw new IllegalArgumentException("Elasticsearch target requires only permissions." + kind);
                }
                Set<String> privileges = strings(permissions.values().get(kind), "permissions." + kind);
                if (cluster) entry.cluster.addAll(privileges);
                else entry.indices.computeIfAbsent(index, ignored -> new TreeSet<>()).addAll(privileges);
            }
        }
        Map<String, ElasticsearchRole> result = new TreeMap<>();
        aggregate.forEach((principal, entry) -> {
            Map<String, List<String>> indices = new TreeMap<>();
            entry.indices.forEach((index, privileges) -> indices.put(index, List.copyOf(privileges)));
            result.put(principal, new ElasticsearchRole(principal, roleName(backendId, principal), indices, entry.cluster, entry.roles));
        });
        return Collections.unmodifiableMap(result);
    }

    private static Set<String> strings(Value value, String field) {
        if (!(value instanceof SetValue set) || set.values().isEmpty()) {
            throw new IllegalArgumentException("Elasticsearch " + field + " must be a non-empty set of strings");
        }
        var result = new TreeSet<String>();
        for (Value item : set.values()) {
            if (!(item instanceof StringValue string) || string.value() == null || string.value().isBlank()) {
                throw new IllegalArgumentException("Elasticsearch " + field + " must contain non-blank strings");
            }
            result.add(string.value());
        }
        return result;
    }

    private static final class MutableAccess {
        final Map<String, Set<String>> indices = new TreeMap<>();
        final Set<String> cluster = new TreeSet<>();
        final Set<String> roles = new TreeSet<>();
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

}
