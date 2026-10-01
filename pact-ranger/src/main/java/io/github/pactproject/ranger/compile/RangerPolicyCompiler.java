package io.github.pactproject.ranger.compile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.pactproject.api.Access;
import io.github.pactproject.api.PactState;
import io.github.pactproject.api.value.BooleanValue;
import io.github.pactproject.api.value.ObjectValue;
import io.github.pactproject.api.value.SetValue;
import io.github.pactproject.api.value.StringValue;
import io.github.pactproject.api.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

public final class RangerPolicyCompiler {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Logger LOGGER =
            LoggerFactory.getLogger(RangerPolicyCompiler.class);
    private static final String MANAGED_LABEL = "managed";
    private static final String TIME_ZONE = "Europe/Moscow";
    private static final Pattern RANGER_DATE_TIME =
            Pattern.compile("\\d{4}/\\d{2}/\\d{2} \\d{2}:\\d{2}:\\d{2}");
    private static final Set<String> CONTRACT_ACCESS_FIELDS = Set.of(
            "permissions",
            "denyPermissions",
            "conditions",
            "validitySchedule",
            "override",
            "dataMask",
            "rowFilter"
    );
    private static final Map<String, String> CONTRACT_MASK_TYPES = Map.of(
            "mask", "MASK",
            "showLast4", "MASK_SHOW_LAST_4",
            "showFirst4", "MASK_SHOW_FIRST_4",
            "hash", "MASK_HASH",
            "null", "MASK_NULL",
            "none", "MASK_NONE",
            "dateShowYear", "MASK_DATE_SHOW_YEAR",
            "custom", "CUSTOM"
    );

    private RangerPolicyCompiler() {
    }

    public static List<ObjectNode> compile(
            PactState state,
            RangerServiceDefinition serviceDefinition,
            String serviceType,
            String serviceName
    ) {
        Map<String, PolicyBuilder> policies = new TreeMap<>();
        for (Access access : state.accesses()) {
            compileAccess(
                    access,
                    state,
                    serviceDefinition,
                    serviceType,
                    serviceName,
                    policies
            );
        }

        return policies.values().stream()
                .map(PolicyBuilder::toPolicy)
                .toList();
    }

    private static void compileAccess(
            Access access,
            PactState state,
            RangerServiceDefinition definition,
            String serviceType,
            String serviceName,
            Map<String, PolicyBuilder> policies
    ) {
        if (!serviceType.equals(access.resource().backendId())) {
            throw new IllegalArgumentException(
                    "Access backend '" + access.resource().backendId()
                            + "' does not match Ranger service-type '"
                            + serviceType + "'"
            );
        }
        validateTarget(access.resource().target(), definition);
        validateAccessAttributes(access);

        Map<String, Set<String>> allow =
                permissionMap(access.attributes().get("permissions"));
        Map<String, Set<String>> deny =
                permissionMap(access.attributes().get("denyPermissions"));
        validatePermissionResources(allow, definition);
        validatePermissionResources(deny, definition);
        compilePermissions(
                access,
                allow,
                deny,
                definition,
                serviceType,
                serviceName,
                policies
        );
        compileTransformations(
                access,
                state,
                definition,
                serviceType,
                serviceName,
                policies
        );
    }

    private static void compilePermissions(
            Access access,
            Map<String, Set<String>> allow,
            Map<String, Set<String>> deny,
            RangerServiceDefinition definition,
            String serviceType,
            String serviceName,
            Map<String, PolicyBuilder> policies
    ) {
        Set<String> permissionLevels = new HashSet<>(allow.keySet());
        permissionLevels.addAll(deny.keySet());
        for (String level : permissionLevels) {
            Set<String> allowForLevel = allow.getOrDefault(level, Set.of());
            Set<String> denyForLevel = deny.getOrDefault(level, Set.of());
            if (allowForLevel.isEmpty() && denyForLevel.isEmpty()) {
                continue;
            }
            if (!definition.containsResource(level)) {
                throw new IllegalArgumentException(
                        "Permission map references unknown Ranger resource: " + level
                );
            }
            Map<String, String> resources = resourcesForLevel(
                    access,
                    level,
                    definition
            );
            PolicyBuilder policy = policy(
                    policies,
                    policyIdentity(
                            serviceType,
                            serviceName,
                            resources,
                            access,
                            0
                    ),
                    serviceType,
                    serviceName,
                    resources,
                    access,
                    0
            );

            addPermissions(
                    policy.allow,
                    access.principal(),
                    allowForLevel,
                    level,
                    definition
            );
            addPermissions(
                    policy.deny,
                    access.principal(),
                    denyForLevel,
                    level,
                    definition
            );
        }
    }

    private static void compileTransformations(
            Access access,
            PactState state,
            RangerServiceDefinition definition,
            String serviceType,
            String serviceName,
            Map<String, PolicyBuilder> policies
    ) {
        Value maskValue = access.attributes().get("dataMask");
        Value filterValue = access.attributes().get("rowFilter");

        if (maskValue != null) {
            RangerServiceDefinition.TransformationDefinition maskDefinition =
                    definition.dataMask();
            if (maskDefinition == null) {
                throw new IllegalArgumentException(
                        "Ranger ServiceDef does not support dataMask"
                );
            }
            ObjectValue mask = requireObject(maskValue, "dataMask");
            validateObjectFields(mask, Set.of("type", "expression"), "dataMask");
            String type = requireString(mask.values().get("type"), "dataMask.type");
            String expression = optionalString(
                    mask.values().get("expression"),
                    "dataMask.expression"
            );
            if ("custom".equals(type) && (expression == null || expression.isBlank())) {
                throw new IllegalArgumentException(
                        "Custom data mask requires a non-empty expression"
                );
            }
            if (!"custom".equals(type) && expression != null) {
                throw new IllegalArgumentException(
                        "Only custom data masks support an expression"
                );
            }
            String rangerType = CONTRACT_MASK_TYPES.get(type);
            if (rangerType == null
                    || !maskDefinition.maskTypes().contains(rangerType)) {
                throw new IllegalArgumentException(
                        "Data mask type '" + type
                                + "' is not supported by the Ranger ServiceDef"
                );
            }
            Map<String, String> resources = transformationResources(
                    access,
                    definition,
                    maskDefinition,
                    "dataMask"
            );
            validateTransformationAccessTypes(
                    maskDefinition,
                    definition,
                    resources
            );
            warnIfMissingSelect(
                    access,
                    state,
                    definition.leafResources(maskDefinition.resources()),
                    "dataMask"
            );
            PolicyBuilder policy = policy(
                    policies,
                    policyIdentity(
                            serviceType,
                            serviceName,
                            resources,
                            access,
                            1
                    ),
                    serviceType,
                    serviceName,
                    resources,
                    access,
                    1
            );
            policy.masks.add(new Transformation(
                    access.principal(),
                    rangerType,
                    expression,
                    maskDefinition.accessTypes()
            ));
        }

        if (filterValue != null) {
            RangerServiceDefinition.TransformationDefinition filterDefinition =
                    definition.rowFilter();
            if (filterDefinition == null) {
                throw new IllegalArgumentException(
                        "Ranger ServiceDef does not support rowFilter"
                );
            }
            ObjectValue filter = requireObject(filterValue, "rowFilter");
            validateObjectFields(filter, Set.of("expression"), "rowFilter");
            String expression = requireString(
                    filter.values().get("expression"),
                    "rowFilter.expression"
            );
            if (expression.isBlank()) {
                throw new IllegalArgumentException(
                        "Row filter expression must not be empty"
                );
            }
            Map<String, String> resources = transformationResources(
                    access,
                    definition,
                    filterDefinition,
                    "rowFilter"
            );
            validateTransformationAccessTypes(
                    filterDefinition,
                    definition,
                    resources
            );
            warnIfMissingSelect(
                    access,
                    state,
                    definition.leafResources(filterDefinition.resources()),
                    "rowFilter"
            );
            PolicyBuilder policy = policy(
                    policies,
                    policyIdentity(
                            serviceType,
                            serviceName,
                            resources,
                            access,
                            2
                    ),
                    serviceType,
                    serviceName,
                    resources,
                    access,
                    2
            );
            policy.rowFilters.add(new Transformation(
                    access.principal(),
                    null,
                    expression,
                    filterDefinition.accessTypes()
            ));
        }
    }

    private static void validateAccessAttributes(Access access) {
        if (access.principal().isBlank()) {
            throw new IllegalArgumentException(
                    "Ranger access principal must not be empty"
            );
        }
        for (String field : access.attributes().keySet()) {
            if (!CONTRACT_ACCESS_FIELDS.contains(field)) {
                throw new IllegalArgumentException(
                        "Unsupported Ranger access field: " + field
                );
            }
        }
        Value conditions = access.attributes().get("conditions");
        if (conditions != null) {
            stringSet(conditions, "conditions");
        }
        Value schedules = access.attributes().get("validitySchedule");
        if (schedules != null) {
            validitySchedules(schedules);
        }
        boolAttribute(access, "override");
    }

    private static PolicyBuilder policy(
            Map<String, PolicyBuilder> policies,
            ObjectNode identity,
            String serviceType,
            String serviceName,
            Map<String, String> resources,
            Access access,
            int policyType
    ) {
        String key = canonical(identity);
        return policies.computeIfAbsent(
                key,
                ignored -> new PolicyBuilder(
                        serviceType,
                        serviceName,
                        resources,
                        conditions(access.attributes().get("conditions")),
                        validitySchedules(access.attributes().get("validitySchedule")),
                        boolAttribute(access, "override"),
                        policyType
                )
        );
    }

    private static ObjectNode policyIdentity(
            String serviceType,
            String serviceName,
            Map<String, String> resources,
            Access access,
            int policyType
    ) {
        ObjectNode identity = MAPPER.createObjectNode();
        identity.put("serviceType", serviceType);
        identity.put("service", serviceName);
        identity.set("resources", resourcesNode(resources));
        identity.set("conditions", conditions(access.attributes().get("conditions")));
        identity.set("validitySchedules",
                validitySchedules(access.attributes().get("validitySchedule")));
        identity.put("isOverride", boolAttribute(access, "override"));
        identity.put("policyType", policyType);
        return identity;
    }

    private static Map<String, String> resourcesForLevel(
            Access access,
            String level,
            RangerServiceDefinition definition
    ) {
        Map<String, String> target = access.resource().target();
        if (!target.containsKey(level)) {
            throw new IllegalArgumentException(
                    "Permission for Ranger resource '" + level
                            + "' requires a matching target field"
            );
        }

        Set<String> chain = definition.ancestorsInclusive(level);
        Map<String, String> result = new TreeMap<>();
        for (String name : chain) {
            String value = target.get(name);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(
                        "Ranger resource '" + level
                                + "' requires parent target '" + name + "'"
                );
            }
            result.put(name, value);
        }
        addRecursionFlags(result, target, definition);
        return result;
    }

    private static void validateTarget(
            Map<String, String> target,
            RangerServiceDefinition definition
    ) {
        for (Map.Entry<String, String> entry : target.entrySet()) {
            String name = entry.getKey();
            if (definition.containsResource(name)) {
                if (entry.getValue().isBlank()) {
                    throw new IllegalArgumentException(
                            "Ranger resource target '" + name + "' must not be empty"
                    );
                }
                for (String ancestor : definition.ancestorsInclusive(name)) {
                    String value = target.get(ancestor);
                    if (value == null || value.isBlank()) {
                        throw new IllegalArgumentException(
                                "Ranger resource '" + name
                                        + "' requires parent target '" + ancestor + "'"
                        );
                    }
                }
                continue;
            }

            if (name.startsWith("is") && name.endsWith("Recursive")
                    && name.length() > "isRecursive".length()) {
                String resourceName = Character.toLowerCase(name.charAt(2))
                        + name.substring(3, name.length() - "Recursive".length());
                if (!definition.containsResource(resourceName)
                        || !definition.recursiveSupported(resourceName)) {
                    throw new IllegalArgumentException(
                            "Unknown or non-recursive Ranger target field: " + name
                    );
                }
                if (!target.containsKey(resourceName)) {
                    throw new IllegalArgumentException(
                            "Recursion flag '" + name
                                    + "' requires target '" + resourceName + "'"
                    );
                }
                if (!"true".equals(entry.getValue())
                        && !"false".equals(entry.getValue())) {
                    throw new IllegalArgumentException(
                            "Ranger recursion flag '" + name
                                    + "' must be boolean"
                    );
                }
                continue;
            }

            throw new IllegalArgumentException(
                    "Unknown Ranger resource target field: " + name
            );
        }
    }

    private static void validatePermissionResources(
            Map<String, Set<String>> permissions,
            RangerServiceDefinition definition
    ) {
        for (String resource : permissions.keySet()) {
            if (!definition.containsResource(resource)) {
                throw new IllegalArgumentException(
                        "Permission map references unknown Ranger resource: "
                                + resource
                );
            }
        }
    }

    private static Map<String, String> transformationResources(
            Access access,
            RangerServiceDefinition serviceDefinition,
            RangerServiceDefinition.TransformationDefinition transformationDefinition,
            String field
    ) {
        Map<String, String> target = access.resource().target();
        Map<String, String> resources = new TreeMap<>();
        for (String name : transformationDefinition.resources()) {
            if (!serviceDefinition.containsResource(name)) {
                throw new IllegalArgumentException(
                        field + " references unknown Ranger resource '" + name + "'"
                );
            }
            String value = target.get(name);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(
                        field + " requires Ranger resource target '" + name + "'"
                );
            }
            for (String ancestor
                    : serviceDefinition.ancestorsInclusive(name)) {
                String ancestorValue = target.get(ancestor);
                if (ancestorValue == null || ancestorValue.isBlank()) {
                    throw new IllegalArgumentException(
                            field + " requires Ranger resource target '"
                                    + ancestor + "'"
                    );
                }
                resources.put(ancestor, ancestorValue);
            }
        }
        return resources;
    }

    private static void validateTransformationAccessTypes(
            RangerServiceDefinition.TransformationDefinition transformation,
            RangerServiceDefinition serviceDefinition,
            Map<String, String> resources
    ) {
        for (String accessType : transformation.accessTypes()) {
            if (!serviceDefinition.accessTypes().contains(accessType)) {
                throw new IllegalArgumentException(
                        "Ranger ServiceDef transformation uses unknown access type: "
                                + accessType
                );
            }
            for (String resource : resources.keySet()) {
                serviceDefinition.validateAccessType(resource, accessType);
            }
        }
    }

    private static void addRecursionFlags(
            Map<String, String> resources,
            Map<String, String> target,
            RangerServiceDefinition definition
    ) {
        for (String resourceName : resources.keySet().toArray(String[]::new)) {
            String flag = "is" + Character.toUpperCase(resourceName.charAt(0))
                    + resourceName.substring(1) + "Recursive";
            String recursive = target.get(flag);
            if (recursive != null) {
                if (!definition.recursiveSupported(resourceName)
                        && Boolean.parseBoolean(recursive)) {
                    throw new IllegalArgumentException(
                            "Ranger resource '" + resourceName
                                    + "' does not support recursion"
                    );
                }
                resources.put(resourceName + "\u0000recursive", recursive);
            }
        }
    }

    private static void addPermissions(
            Map<String, Set<String>> destination,
            String principal,
            Set<String> permissions,
            String resource,
            RangerServiceDefinition definition
    ) {
        for (String permission : permissions) {
            definition.validateAccessType(resource, permission);
        }
        if (!permissions.isEmpty()) {
            destination.computeIfAbsent(principal, ignored -> new TreeSet<>())
                    .addAll(permissions);
        }
    }

    private static Map<String, Set<String>> permissionMap(Value value) {
        if (value == null) {
            return Map.of();
        }
        ObjectValue object = requireObject(value, "permissions");
        Map<String, Set<String>> result = new TreeMap<>();
        for (Map.Entry<String, ? extends Value> entry
                : object.values().entrySet()) {
            Set<String> permissions = stringSet(entry.getValue(), entry.getKey());
            result.put(entry.getKey(), permissions);
        }
        return result;
    }

    private static ArrayNode conditions(Value value) {
        ArrayNode result = MAPPER.createArrayNode();
        if (value == null) {
            return result;
        }
        List<String> conditions = new ArrayList<>(stringSet(value, "conditions"));
        conditions.sort(String::compareTo);
        for (String condition : conditions) {
            ObjectNode node = result.addObject();
            node.put("type", "_expression");
            node.putArray("values").add(condition);
        }
        return result;
    }

    private static ArrayNode validitySchedules(Value value) {
        ArrayNode result = MAPPER.createArrayNode();
        if (value == null) {
            return result;
        }
        if (!(value instanceof SetValue schedules)) {
            throw new IllegalArgumentException(
                    "validitySchedule attribute must be a set"
            );
        }
        List<ObjectNode> normalized = new ArrayList<>();
        for (Value scheduleValue : schedules.values()) {
            ObjectValue schedule = requireObject(
                    scheduleValue,
                    "validitySchedule item"
            );
            ObjectNode node = MAPPER.createObjectNode();
            String start = optionalString(schedule.values().get("start"), "schedule.start");
            String end = optionalString(schedule.values().get("end"), "schedule.end");
            if (start == null && end == null) {
                throw new IllegalArgumentException(
                        "Validity schedule must contain start or end"
                );
            }
            if (start != null) {
                validateScheduleTime(start, "schedule.start");
                node.put("startTime", start);
            }
            if (end != null) {
                validateScheduleTime(end, "schedule.end");
                node.put("endTime", end);
            }
            node.put("timeZone", TIME_ZONE);
            normalized.add(node);
        }
        normalized.sort(Comparator.comparing(RangerPolicyCompiler::canonical));
        normalized.forEach(result::add);
        return result;
    }

    private static void validateScheduleTime(String value, String field) {
        if (!RANGER_DATE_TIME.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "'" + field
                            + "' must use YYYY/MM/DD HH:mm:ss format"
            );
        }
    }

    private static void warnIfMissingSelect(
            Access transformationAccess,
            PactState state,
            Set<String> resourceLevels,
            String transformation
    ) {
        Set<String> missingUsers = new TreeSet<>();
        for (String resourceLevel : resourceLevels) {
            String user = transformationAccess.principal();
            boolean hasSelect = state.accesses().stream()
                    .filter(access ->
                            access.principal().equals(user)
                                    && access.resource().equals(
                                    transformationAccess.resource()
                            ))
                    .map(access -> permissionMap(
                            access.attributes().get("permissions")
                    ))
                    .map(permissions -> permissions.getOrDefault(
                            resourceLevel,
                            Set.of()
                    ))
                    .anyMatch(permissions -> permissions.contains("select"));
            if (!hasSelect) {
                missingUsers.add(user);
            }
        }
        if (!missingUsers.isEmpty()) {
            LOGGER.warn(
                    "{} requires select permission for users {} at {}",
                    transformation,
                    missingUsers,
                    transformationAccess.resource().target()
            );
        }
    }

    private static ObjectNode resourcesNode(Map<String, String> resources) {
        ObjectNode result = MAPPER.createObjectNode();
        for (Map.Entry<String, String> entry : new TreeMap<>(resources).entrySet()) {
            String resourceName = entry.getKey();
            if (resourceName.contains("\u0000")) {
                continue;
            }
            ObjectNode node = result.putObject(resourceName);
            node.putArray("values").add(entry.getValue());
            node.put("isExcludes", false);
            node.put(
                    "isRecursive",
                    Boolean.parseBoolean(
                            resources.getOrDefault(
                                    resourceName + "\u0000recursive",
                                    "false"
                            )
                    )
            );
        }
        return result;
    }

    private static Set<String> stringSet(Value value, String field) {
        if (!(value instanceof SetValue set)) {
            throw new IllegalArgumentException(
                    "'" + field + "' must be a set of strings"
            );
        }
        Set<String> result = new TreeSet<>();
        for (Value item : set.values()) {
            if (!(item instanceof StringValue string)) {
                throw new IllegalArgumentException(
                        "'" + field + "' must contain only strings"
                );
            }
            result.add(string.value());
        }
        return Set.copyOf(result);
    }

    private static ObjectValue requireObject(Value value, String field) {
        if (!(value instanceof ObjectValue object)) {
            throw new IllegalArgumentException(
                    "'" + field + "' must be an object"
            );
        }
        return object;
    }

    private static void validateObjectFields(
            ObjectValue object,
            Set<String> supported,
            String field
    ) {
        for (String key : object.values().keySet()) {
            if (!supported.contains(key)) {
                throw new IllegalArgumentException(
                        "Unsupported " + field + " field: " + key
                );
            }
        }
    }

    private static String requireString(Value value, String field) {
        String result = optionalString(value, field);
        if (result == null) {
            throw new IllegalArgumentException(
                    "'" + field + "' is required"
            );
        }
        return result;
    }

    private static String optionalString(Value value, String field) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof StringValue string)) {
            throw new IllegalArgumentException(
                    "'" + field + "' must be a string"
            );
        }
        return string.value();
    }

    private static boolean boolAttribute(Access access, String name) {
        Value value = access.attributes().get(name);
        if (value == null) {
            return false;
        }
        if (!(value instanceof BooleanValue bool)) {
            throw new IllegalArgumentException(
                    "'" + name + "' attribute must be boolean"
            );
        }
        return bool.value();
    }

    private static String canonical(JsonNode node) {
        if (node.isObject()) {
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            names.sort(String::compareTo);
            ObjectNode sorted = MAPPER.createObjectNode();
            for (String name : names) {
                sorted.set(name, canonicalNode(node.get(name)));
            }
            return sorted.toString();
        }
        return canonicalNode(node).toString();
    }

    private static JsonNode canonicalNode(JsonNode node) {
        if (!node.isObject()) {
            if (node.isArray()) {
                ArrayNode result = MAPPER.createArrayNode();
                node.forEach(item -> result.add(canonicalNode(item)));
                return result;
            }
            return node.deepCopy();
        }
        ObjectNode result = MAPPER.createObjectNode();
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        names.sort(String::compareTo);
        for (String name : names) {
            result.set(name, canonicalNode(node.get(name)));
        }
        return result;
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private record Transformation(
            String principal,
            String type,
            String expression,
            Set<String> accessTypes
    ) {
        private Transformation {
            accessTypes = Set.copyOf(accessTypes);
        }
    }

    private static final class PolicyBuilder {
        private final String serviceType;
        private final String serviceName;
        private final Map<String, String> resources;
        private final ArrayNode conditions;
        private final ArrayNode schedules;
        private final boolean override;
        private final int policyType;
        private final Map<String, Set<String>> allow = new TreeMap<>();
        private final Map<String, Set<String>> deny = new TreeMap<>();
        private final Set<Transformation> masks = new HashSet<>();
        private final Set<Transformation> rowFilters = new HashSet<>();

        private PolicyBuilder(
                String serviceType,
                String serviceName,
                Map<String, String> resources,
                ArrayNode conditions,
                ArrayNode schedules,
                boolean override,
                int policyType
        ) {
            this.serviceType = serviceType;
            this.serviceName = serviceName;
            this.resources = Map.copyOf(resources);
            this.conditions = conditions;
            this.schedules = schedules;
            this.override = override;
            this.policyType = policyType;
        }

        private ObjectNode toPolicy() {
            ObjectNode policy = MAPPER.createObjectNode();
            policy.put("isEnabled", true);
            policy.put("service", serviceName);
            policy.put(
                    "name",
                    "managed:" + serviceType + ":"
                            + hash(canonical(policyIdentityForName()))
            );
            policy.put("policyType", policyType);
            policy.put("policyPriority", 0);
            policy.put("isAuditEnabled", true);
            policy.set("resources", resourcesNode(resources));
            policy.put("serviceType", serviceType);
            policy.putArray("policyLabels").add(MANAGED_LABEL);
            policy.put("isDenyAllElse", false);
            policy.put("isOverride", override);
            policy.set("conditions", conditions.deepCopy());
            policy.set("validitySchedules", schedules.deepCopy());

            if (policyType == 0) {
                policy.set("policyItems", policyItems(allow));
                policy.set("denyPolicyItems", policyItems(deny));
            }
            else if (policyType == 1) {
                ArrayNode items = policy.putArray("dataMaskPolicyItems");
                masks.stream()
                        .sorted(Comparator.comparing(
                                item -> item.principal() + "\u0000"
                                        + item.type() + "\u0000"
                                        + item.expression()
                        ))
                        .forEach(item -> {
                            ObjectNode node = baseItem(
                                    item.principal(),
                                    item.accessTypes()
                            );
                            ObjectNode info = node.putObject("dataMaskInfo");
                            info.put("dataMaskType", item.type());
                            if (item.expression() != null) {
                                info.put("valueExpr", item.expression());
                            }
                            items.add(node);
                        });
            }
            else {
                ArrayNode items = policy.putArray("rowFilterPolicyItems");
                rowFilters.stream()
                        .sorted(Comparator.comparing(
                                item -> item.principal() + "\u0000"
                                        + item.expression()
                        ))
                        .forEach(item -> {
                            ObjectNode node = baseItem(
                                    item.principal(),
                                    item.accessTypes()
                            );
                            node.putObject("rowFilterInfo")
                                    .put("filterExpr", item.expression());
                            items.add(node);
                        });
            }
            return policy;
        }

        private ObjectNode policyIdentityForName() {
            ObjectNode identity = MAPPER.createObjectNode();
            identity.put("serviceType", serviceType);
            identity.put("service", serviceName);
            identity.set("resources", resourcesNode(resources));
            identity.set("conditions", conditions);
            identity.set("validitySchedules", schedules);
            identity.put("isOverride", override);
            identity.put("policyType", policyType);
            return identity;
        }
    }

    private static ArrayNode policyItems(
            Map<String, Set<String>> principals
    ) {
        ArrayNode result = MAPPER.createArrayNode();
        principals.forEach((principal, permissions) -> {
            ObjectNode item = baseItem(principal, Set.of());
            ArrayNode accesses = item.withArray("accesses");
            new TreeSet<>(permissions).forEach(permission -> {
                ObjectNode access = accesses.addObject();
                access.put("type", permission);
                access.put("isAllowed", true);
            });
            result.add(item);
        });
        return result;
    }

    private static ObjectNode baseItem(
            String principal,
            Set<String> permissions
    ) {
        ObjectNode item = MAPPER.createObjectNode();
        item.putArray("users").add(principal);
        item.putArray("groups");
        item.putArray("roles");
        ArrayNode accesses = item.putArray("accesses");
        for (String permission : new TreeSet<>(permissions)) {
            ObjectNode access = accesses.addObject();
            access.put("type", permission);
            access.put("isAllowed", true);
        }
        item.put("delegateAdmin", false);
        return item;
    }
}
