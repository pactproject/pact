package io.github.pactproject.elasticsearch.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.pactproject.elasticsearch.model.ElasticsearchRole;
import java.util.Set;
import java.util.TreeSet;

/** Canonical security documents, including set semantics and server defaults. */
final class ElasticsearchDocuments {
    static final ObjectMapper JSON = new ObjectMapper();
    private ElasticsearchDocuments() { }

    static ObjectNode role(String backendId, ElasticsearchRole role) {
        var definition = JSON.createObjectNode();
        var cluster = definition.putArray("cluster");
        role.cluster().forEach(cluster::add);
        var indices = definition.putArray("indices");
        role.indices().forEach((index, privileges) -> {
            var entry = indices.addObject();
            entry.putArray("names").add(index);
            var values = entry.putArray("privileges");
            privileges.forEach(values::add);
        });
        definition.putArray("applications");
        definition.putArray("run_as");
        var metadata = definition.putObject("metadata");
        metadata.put("pact_managed_by", "pact");
        metadata.put("pact_backend_id", backendId);
        metadata.put("pact_principal", role.principal());
        return definition;
    }

    static boolean owned(JsonNode role, String backendId, String principal) {
        var metadata = role.path("metadata");
        return metadata.path("pact_managed_by").asText().equals("pact")
                && metadata.path("pact_backend_id").asText().equals(backendId)
                && metadata.path("pact_principal").asText().equals(principal);
    }

    static Set<String> roles(JsonNode user) {
        var roles = new TreeSet<String>();
        if (user != null) user.path("roles").forEach(role -> roles.add(role.asText()));
        return roles;
    }

    static ObjectNode userUpdate(JsonNode user, Set<String> roles) {
        var body = JSON.createObjectNode();
        var values = body.putArray("roles");
        roles.stream().sorted().forEach(values::add);
        for (String field : Set.of("full_name", "email", "metadata", "enabled")) {
            if (user.hasNonNull(field)) body.set(field, user.get(field).deepCopy());
        }
        return body;
    }

    static boolean sameRole(JsonNode first, JsonNode second) {
        return normalizeRole(first).equals(normalizeRole(second));
    }

    private static JsonNode normalizeRole(JsonNode role) {
        if (role == null) return JSON.nullNode();
        ObjectNode copy = role.deepCopy();
        copy.remove("transient_metadata");
        if (!copy.hasNonNull("description") || copy.path("description").asText().isEmpty()) copy.remove("description");
        for (String field : Set.of("cluster", "indices", "applications", "run_as", "remote_indices", "remote_cluster")) {
            if (!copy.hasNonNull(field)) copy.putArray(field);
        }
        if (!copy.hasNonNull("global")) copy.putObject("global");
        for (JsonNode index : copy.path("indices")) {
            if (index instanceof ObjectNode object && !object.path("allow_restricted_indices").asBoolean()) {
                object.remove("allow_restricted_indices");
            }
        }
        return canonical(copy);
    }

    private static JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            var result = JSON.createObjectNode();
            var fields = new TreeSet<String>();
            node.fieldNames().forEachRemaining(fields::add);
            fields.forEach(field -> result.set(field, canonical(node.get(field))));
            return result;
        }
        if (node.isArray()) {
            var values = new java.util.ArrayList<JsonNode>();
            node.forEach(value -> values.add(canonical(value)));
            values.sort(java.util.Comparator.comparing(JsonNode::toString));
            return JSON.createArrayNode().addAll(values);
        }
        return node;
    }
}
