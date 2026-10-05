package io.github.pactproject.ranger.compile;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public final class RangerServiceDefinition {
    private final Map<String, ResourceDefinition> resources;
    private final Set<String> accessTypes;
    private final TransformationDefinition dataMask;
    private final TransformationDefinition rowFilter;

    private RangerServiceDefinition(
            Map<String, ResourceDefinition> resources,
            Set<String> accessTypes,
            TransformationDefinition dataMask,
            TransformationDefinition rowFilter
    ) {
        this.resources = Map.copyOf(resources);
        this.accessTypes = Set.copyOf(accessTypes);
        this.dataMask = dataMask;
        this.rowFilter = rowFilter;
    }

    public static RangerServiceDefinition parse(
            JsonNode response,
            String expectedName
    ) {
        JsonNode definition = selectDefinition(response, expectedName);
        JsonNode resourceNodes = definition.path("resources");
        JsonNode accessTypeNodes = definition.path("accessTypes");
        if (!resourceNodes.isArray() || !accessTypeNodes.isArray()) {
            throw new IllegalArgumentException(
                    "Ranger ServiceDef must contain resources and accessTypes arrays"
            );
        }

        Map<String, ResourceDefinition> resources = new HashMap<>();
        for (JsonNode resource : resourceNodes) {
            String name = requiredText(resource, "name", "ServiceDef resource");
            JsonNode parentNode = resource.get("parent");
            String parent = parentNode == null || parentNode.isNull()
                    || parentNode.asText().isBlank()
                    ? null
                    : parentNode.asText();
            Set<String> restrictions = new HashSet<>();
            JsonNode restrictionNodes = resource.path("accessTypeRestrictions");
            if (restrictionNodes.isArray()) {
                restrictionNodes.forEach(node -> restrictions.add(node.asText()));
            }
            boolean recursiveSupported =
                    resource.path("recursiveSupported").asBoolean(false);
            if (resources.put(
                    name,
                    new ResourceDefinition(parent, restrictions, recursiveSupported)
            ) != null) {
                throw new IllegalArgumentException(
                        "Duplicate resource in Ranger ServiceDef: " + name
                );
            }
        }

        Set<String> accessTypes = new HashSet<>();
        for (JsonNode accessType : accessTypeNodes) {
            String name = requiredText(
                    accessType,
                    "name",
                    "ServiceDef access type"
            );
            if (!accessTypes.add(name)) {
                throw new IllegalArgumentException(
                        "Duplicate access type in Ranger ServiceDef: " + name
                );
            }
        }
        if (resources.isEmpty()) {
            throw new IllegalArgumentException(
                    "Ranger ServiceDef has no resources"
            );
        }

        RangerServiceDefinition result =
                new RangerServiceDefinition(
                        resources,
                        accessTypes,
                        parseTransformationDefinition(
                                definition.get("dataMaskDef"),
                                "dataMaskDef",
                                true
                        ),
                        parseTransformationDefinition(
                                definition.get("rowFilterDef"),
                                "rowFilterDef",
                                false
                        )
                );
        result.validateParents();
        return result;
    }

    public Set<String> resourceNames() {
        return resources.keySet();
    }

    public Set<String> accessTypes() {
        return accessTypes;
    }

    public TransformationDefinition dataMask() {
        return dataMask;
    }

    public TransformationDefinition rowFilter() {
        return rowFilter;
    }

    public boolean containsResource(String name) {
        return resources.containsKey(name);
    }

    public boolean recursiveSupported(String resourceName) {
        return requireResource(resourceName).recursiveSupported();
    }

    public Set<String> ancestorsInclusive(String resourceName) {
        Set<String> result = new HashSet<>();
        String current = resourceName;
        while (current != null) {
            if (!result.add(current)) {
                throw new IllegalArgumentException(
                        "Cyclic parent chain in Ranger ServiceDef at " + current
                );
            }
            current = requireResource(current).parent();
        }
        return Set.copyOf(result);
    }

    public Set<String> leafResources(Set<String> subset) {
        Set<String> leaves = new TreeSet<>(subset);
        for (String candidate : subset) {
            for (String ancestor : ancestorsInclusive(candidate)) {
                if (!candidate.equals(ancestor) && subset.contains(ancestor)) {
                    leaves.remove(ancestor);
                }
            }
        }
        return Set.copyOf(leaves);
    }

    public void validateAccessType(String resourceName, String accessType) {
        ResourceDefinition resource = requireResource(resourceName);
        if (!accessTypes.contains(accessType)) {
            throw new IllegalArgumentException(
                    "Unsupported Ranger access type '" + accessType + "'"
            );
        }
        if (!resource.accessTypeRestrictions().isEmpty()
                && !resource.accessTypeRestrictions().contains(accessType)) {
            throw new IllegalArgumentException(
                    "Ranger access type '" + accessType
                            + "' is not allowed for resource '" + resourceName + "'"
            );
        }
    }

    private ResourceDefinition requireResource(String name) {
        ResourceDefinition resource = resources.get(name);
        if (resource == null) {
            throw new IllegalArgumentException(
                    "Unknown Ranger resource in ServiceDef: " + name
            );
        }
        return resource;
    }

    private void validateParents() {
        for (Map.Entry<String, ResourceDefinition> entry : resources.entrySet()) {
            String parent = entry.getValue().parent();
            if (parent != null && !resources.containsKey(parent)) {
                throw new IllegalArgumentException(
                        "Unknown parent '" + parent + "' for Ranger resource '"
                                + entry.getKey() + "'"
                );
            }
            ancestorsInclusive(entry.getKey());
        }
    }

    private static TransformationDefinition parseTransformationDefinition(
            JsonNode node,
            String field,
            boolean mask
    ) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject() && node.isEmpty()) {
            return null;
        }
        JsonNode resourceNodes = node.path("resources");
        JsonNode accessNodes = node.path("accessTypes");
        if (!resourceNodes.isArray()
                || resourceNodes.isEmpty()
                || !accessNodes.isArray()
                || accessNodes.isEmpty()) {
            throw new IllegalArgumentException(
                    "Ranger ServiceDef " + field
                            + " must contain non-empty resources and accessTypes arrays"
            );
        }
        Set<String> resources = parseNamedArray(
                resourceNodes,
                field + " resource"
        );
        Set<String> accessTypes = parseNamedArray(
                accessNodes,
                field + " access type"
        );
        Set<String> maskTypes = new HashSet<>();
        if (mask) {
            JsonNode maskTypeNodes = node.path("maskTypes");
            if (!maskTypeNodes.isArray() || maskTypeNodes.isEmpty()) {
                throw new IllegalArgumentException(
                        "Ranger ServiceDef dataMaskDef must contain maskTypes"
                );
            }
            maskTypes = parseNamedArray(maskTypeNodes, "dataMaskDef mask type");
        }
        return new TransformationDefinition(resources, accessTypes, maskTypes);
    }

    private static Set<String> parseNamedArray(JsonNode nodes, String context) {
        Set<String> result = new HashSet<>();
        for (JsonNode node : nodes) {
            String name = requiredText(node, "name", context);
            if (!result.add(name)) {
                throw new IllegalArgumentException(
                        "Duplicate " + context + " in Ranger ServiceDef: " + name
                );
            }
        }
        return Set.copyOf(result);
    }

    private static JsonNode selectDefinition(
            JsonNode response,
            String expectedName
    ) {
        if (response.isArray()) {
            for (JsonNode definition : response) {
                if (expectedName.equals(definition.path("name").asText())) {
                    return definition;
                }
            }
        }

        JsonNode definitions = response.path("serviceDefs");
        if (definitions.isArray()) {
            for (JsonNode definition : definitions) {
                if (expectedName.equals(definition.path("name").asText())) {
                    return definition;
                }
            }
        }

        JsonNode wrappedDefinition = response.path("serviceDef");
        if (expectedName.equals(wrappedDefinition.path("name").asText())) {
            return wrappedDefinition;
        }

        if (expectedName.equals(response.path("name").asText())) {
            return response;
        }
        throw new IllegalArgumentException(
                "Ranger ServiceDef not found: " + expectedName
        );
    }

    private static String requiredText(
            JsonNode node,
            String field,
            String context
    ) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException(
                    context + " requires a non-empty " + field
            );
        }
        return value.asText();
    }

    private record ResourceDefinition(
            String parent,
            Set<String> accessTypeRestrictions,
            boolean recursiveSupported
    ) {
        private ResourceDefinition {
            accessTypeRestrictions = Set.copyOf(accessTypeRestrictions);
        }
    }

    public record TransformationDefinition(
            Set<String> resources,
            Set<String> accessTypes,
            Set<String> maskTypes
    ) {
        public TransformationDefinition {
            resources = Set.copyOf(resources);
            accessTypes = Set.copyOf(accessTypes);
            maskTypes = Set.copyOf(maskTypes);
        }
    }
}
