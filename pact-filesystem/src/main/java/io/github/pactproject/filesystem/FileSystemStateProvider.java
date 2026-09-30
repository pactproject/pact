package io.github.pactproject.filesystem;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.github.pactproject.api.Access;
import io.github.pactproject.api.PactState;
import io.github.pactproject.api.Resource;
import io.github.pactproject.api.StateProvider;
import io.github.pactproject.api.exception.StateProviderException;
import io.github.pactproject.api.value.Value;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

public final class FileSystemStateProvider
        implements StateProvider
{
    private final Path path;
    private final ObjectMapper mapper;

    public FileSystemStateProvider(Path path)
    {
        this.path = path;
        this.mapper = new ObjectMapper(new YAMLFactory());
    }

    @Override
    public PactState load()
            throws StateProviderException
    {
        try {
            JsonNode root = mapper.readTree(path.toFile());

            return parseState(root);
        }
        catch (IOException | RuntimeException e) {
            throw new StateProviderException(
                    "Failed to load PACT state from " + path,
                    e
            );
        }
    }

    private PactState parseState(JsonNode root)
    {
        JsonNode accesses = requiredField(root, "accesses");

        if (!accesses.isArray()) {
            throw new IllegalArgumentException(
                    "'accesses' must be an array"
            );
        }

        Set<Access> result = new HashSet<>();

        for (JsonNode access : accesses) {
            result.add(parseAccess(access));
        }

        return new PactState(result);
    }

    private Access parseAccess(JsonNode node)
    {
        String principal =
                requiredText(node, "principal");

        Resource resource =
                parseResource(requiredObject(node, "resource"));

        Map<String, Value> attributes =
                parseAttributes(
                        requiredObject(node, "attributes")
                );

        return new Access(
                principal,
                resource,
                attributes
        );
    }

    private Resource parseResource(JsonNode node)
    {
        String backendId =
                requiredText(node, "backendId");

        JsonNode target =
                requiredObject(node, "target");

        Map<String, String> targetValues =
                new HashMap<>();

        Iterator<Map.Entry<String, JsonNode>> fields =
                target.fields();

        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field =
                    fields.next();

            if (!field.getValue().isTextual()) {
                throw new IllegalArgumentException(
                        "Resource target '" + field.getKey()
                                + "' must be a string"
                );
            }

            targetValues.put(
                    field.getKey(),
                    field.getValue().textValue()
            );
        }

        return new Resource(
                backendId,
                targetValues
        );
    }

    private Map<String, Value> parseAttributes(JsonNode node)
    {
        Map<String, Value> attributes = new HashMap<>();

        Iterator<Map.Entry<String, JsonNode>> fields =
                node.fields();

        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field =
                    fields.next();

            attributes.put(
                    field.getKey(),
                    parseValue(field.getValue())
            );
        }

        return attributes;
    }

    private Value parseValue(JsonNode node)
    {
        if (node.isTextual()) {
            return Value.string(node.textValue());
        }

        if (node.isBoolean()) {
            return Value.bool(node.booleanValue());
        }

        if (node.isNumber()) {
            return Value.number(
                    node.decimalValue()
            );
        }

        if (node.isArray()) {
            Set<Value> values = new HashSet<>();

            for (JsonNode element : node) {
                values.add(parseValue(element));
            }

            return Value.set(values);
        }

        if (node.isObject()) {
            Map<String, Value> values = new HashMap<>();

            Iterator<Map.Entry<String, JsonNode>> fields =
                    node.fields();

            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field =
                        fields.next();

                values.put(
                        field.getKey(),
                        parseValue(field.getValue())
                );
            }

            return Value.object(values);
        }

        throw new IllegalArgumentException(
                "Unsupported YAML value: " + node
        );
    }

    private static JsonNode requiredField(
            JsonNode node,
            String name)
    {
        JsonNode value = node.get(name);

        if (value == null || value.isNull()) {
            throw new IllegalArgumentException(
                    "Missing required field: " + name
            );
        }

        return value;
    }

    private static JsonNode requiredObject(
            JsonNode node,
            String name)
    {
        JsonNode value = requiredField(node, name);

        if (!value.isObject()) {
            throw new IllegalArgumentException(
                    "'" + name + "' must be an object"
            );
        }

        return value;
    }

    private static String requiredText(
            JsonNode node,
            String name)
    {
        JsonNode value = requiredField(node, name);

        if (!value.isTextual()) {
            throw new IllegalArgumentException(
                    "'" + name + "' must be a string"
            );
        }

        return value.textValue();
    }
}
