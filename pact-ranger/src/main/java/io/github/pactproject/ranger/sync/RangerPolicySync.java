package io.github.pactproject.ranger.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.pactproject.ranger.api.RangerClient;
import io.github.pactproject.ranger.api.RangerClientException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public final class RangerPolicySync {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> SEMANTIC_FIELDS = List.of(
            "name",
            "service",
            "serviceType",
            "resources",
            "policyType",
            "policyPriority",
            "isEnabled",
            "isAuditEnabled",
            "isOverride",
            "isDenyAllElse",
            "policyLabels",
            "policyItems",
            "denyPolicyItems",
            "dataMaskPolicyItems",
            "rowFilterPolicyItems",
            "conditions",
            "validitySchedules"
    );

    private final RangerClient client;
    private final String serviceName;
    private final int pageSize;
    private final boolean managedOnly;

    public RangerPolicySync(
            RangerClient client,
            String serviceName,
            int pageSize
    ) {
        this(client, serviceName, pageSize, false);
    }

    public RangerPolicySync(
            RangerClient client,
            String serviceName,
            int pageSize,
            boolean managedOnly
    ) {
        this.client = client;
        this.serviceName = serviceName;
        this.pageSize = pageSize;
        this.managedOnly = managedOnly;
    }

    public List<JsonNode> getPolicies()
            throws RangerClientException {
        List<JsonNode> policies = client.getPolicies(serviceName, pageSize);
        return policies.stream()
                .map(node -> (JsonNode) node.deepCopy())
                .toList();
    }

    public List<JsonNode> getPoliciesInScope()
            throws RangerClientException {
        return policiesInScope(getPolicies());
    }

    private List<JsonNode> policiesInScope(List<JsonNode> policies) {
        List<JsonNode> policiesInScope = policies
                .stream()
                .filter(policy -> !managedOnly || isManaged(policy))
                .toList();
        indexByName(policiesInScope);
        return policiesInScope;
    }

    public void synchronize(
            List<JsonNode> actual,
            List<? extends JsonNode> desired
    ) throws RangerClientException {
        Map<String, JsonNode> actualByName =
                indexByName(policiesInScope(actual));
        Map<String, JsonNode> desiredByName = indexByName(desired);
        rejectUnmanagedNameCollisions(actual, desiredByName);
        List<JsonNode> creates = new ArrayList<>();
        List<Update> updates = new ArrayList<>();
        List<JsonNode> deletes = new ArrayList<>();

        for (Map.Entry<String, JsonNode> entry : desiredByName.entrySet()) {
            JsonNode current = actualByName.get(entry.getKey());
            if (current == null) {
                creates.add(entry.getValue());
            }
            else if (!semanticallyEqual(current, entry.getValue())) {
                long id = requiredId(current);
                updates.add(new Update(id, entry.getValue()));
            }
        }

        for (Map.Entry<String, JsonNode> entry : actualByName.entrySet()) {
            if (!desiredByName.containsKey(entry.getKey())) {
                deletes.add(entry.getValue());
            }
        }

        Set<String> users = new java.util.TreeSet<>();
        for (JsonNode create : creates) {
            collectUsers(create, users);
        }
        for (Update update : updates) {
            collectUsers(update.policy(), users);
        }
        for (String user : users) {
            client.ensureUser(user);
        }

        for (JsonNode create : creates) {
            client.createPolicy(asObject(stripServerFields(create)));
        }
        for (Update update : updates) {
            client.updatePolicy(
                    update.id(),
                    asObject(stripServerFields(update.policy()))
            );
        }
        for (JsonNode delete : deletes) {
            client.deletePolicy(requiredId(delete));
        }
    }

    private void rejectUnmanagedNameCollisions(
            List<JsonNode> actual,
            Map<String, JsonNode> desiredByName)
    {
        if (!managedOnly) {
            return;
        }
        for (JsonNode policy : actual) {
            String name = policy.path("name").asText();
            if (!isManaged(policy) && desiredByName.containsKey(name)) {
                throw new IllegalArgumentException(
                        "Desired Ranger policy name collides with an "
                                + "unmanaged policy: " + name
                );
            }
        }
    }

    private static void collectUsers(JsonNode policy, Set<String> users) {
        for (String field : List.of(
                "policyItems",
                "denyPolicyItems",
                "dataMaskPolicyItems",
                "rowFilterPolicyItems"
        )) {
            JsonNode items = policy.path(field);
            if (!items.isArray()) {
                continue;
            }
            for (JsonNode item : items) {
                JsonNode itemUsers = item.path("users");
                if (itemUsers.isArray()) {
                    itemUsers.forEach(user -> {
                        if (user.isTextual() && !user.asText().isBlank()) {
                            users.add(user.asText());
                        }
                    });
                }
            }
        }
    }

    private static boolean semanticallyEqual(JsonNode actual, JsonNode desired) {
        return semanticPolicy(actual).equals(semanticPolicy(desired));
    }

    private static JsonNode semanticPolicy(JsonNode policy) {
        ObjectNode result = MAPPER.createObjectNode();
        for (String field : SEMANTIC_FIELDS) {
            JsonNode value = policy.get(field);
            if (value == null || value.isNull()) {
                if (isArrayField(field)) {
                    value = MAPPER.createArrayNode();
                }
                else if (isBooleanField(field)) {
                    value = MAPPER.getNodeFactory().booleanNode(
                            "isEnabled".equals(field)
                                    || "isAuditEnabled".equals(field)
                    );
                }
                else if ("policyType".equals(field)
                        || "policyPriority".equals(field)) {
                    value = MAPPER.getNodeFactory().numberNode(0);
                }
                else {
                    continue;
                }
            }
            result.set(field, normalize(value));
        }
        return result;
    }

    private static JsonNode normalize(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = MAPPER.createObjectNode();
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            names.sort(String::compareTo);
            for (String name : names) {
                result.set(name, normalize(node.get(name)));
            }
            return result;
        }
        if (node.isArray()) {
            List<JsonNode> items = new ArrayList<>();
            node.forEach(item -> items.add(normalize(item)));
            items.sort(Comparator.comparing(JsonNode::toString));
            ArrayNode result = MAPPER.createArrayNode();
            items.forEach(result::add);
            return result;
        }
        return node.deepCopy();
    }

    private static JsonNode stripServerFields(JsonNode policy) {
        ObjectNode result = asObject(policy.deepCopy());
        result.remove(List.of(
                "id",
                "createTime",
                "updateTime",
                "createdBy",
                "updatedBy",
                "version"
        ));
        return result;
    }

    private static Map<String, JsonNode> indexByName(
            List<? extends JsonNode> policies
    ) {
        Map<String, JsonNode> result = new TreeMap<>();
        for (JsonNode policy : policies) {
            String name = policy.path("name").asText();
            if (name.isBlank()) {
                throw new IllegalArgumentException(
                        "Ranger policy has no name"
                );
            }
            if (result.put(name, policy) != null) {
                throw new IllegalArgumentException(
                        "Duplicate Ranger policy name: " + name
                );
            }
        }
        return result;
    }

    private static boolean isManaged(JsonNode policy) {
        JsonNode labels = policy.path("policyLabels");
        if (!labels.isArray()) {
            return false;
        }
        for (JsonNode label : labels) {
            if ("managed".equals(label.asText())) {
                return true;
            }
        }
        return false;
    }

    private static long requiredId(JsonNode policy) {
        JsonNode id = policy.get("id");
        if (id == null || !id.canConvertToLong() || id.asLong() < 1) {
            throw new IllegalArgumentException(
                    "Ranger managed policy has no valid id"
            );
        }
        return id.asLong();
    }

    private static boolean isArrayField(String field) {
        return Set.of(
                "policyLabels",
                "policyItems",
                "denyPolicyItems",
                "dataMaskPolicyItems",
                "rowFilterPolicyItems",
                "conditions",
                "validitySchedules"
        ).contains(field);
    }

    private static boolean isBooleanField(String field) {
        return Set.of(
                "isEnabled",
                "isAuditEnabled",
                "isOverride",
                "isDenyAllElse"
        ).contains(field);
    }

    private static ObjectNode asObject(JsonNode node) {
        if (!(node instanceof ObjectNode object)) {
            throw new IllegalArgumentException(
                    "Ranger policy must be a JSON object"
            );
        }
        return object;
    }

    private record Update(long id, JsonNode policy) {
    }
}
