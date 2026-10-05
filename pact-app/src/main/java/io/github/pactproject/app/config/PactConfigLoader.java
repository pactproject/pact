package io.github.pactproject.app.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PactConfigLoader
{
    private static final Pattern ENVIRONMENT_VARIABLE =
            Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");
    private final ObjectMapper mapper;
    private final Function<String, String> environment;

    public PactConfigLoader()
    {
        this(System::getenv);
    }

    PactConfigLoader(Function<String, String> environment)
    {
        this.mapper = new ObjectMapper(new YAMLFactory());
        this.environment = environment;
    }

    public PactConfig load(Path path)
            throws PactConfigException
    {
        try {
            JsonNode source = mapper.readTree(path.toFile());
            return mapper.treeToValue(
                    resolveEnvironmentVariables(source),
                    PactConfig.class
            );
        }
        catch (IOException | RuntimeException e) {
            throw new PactConfigException(
                    "Failed to load PACT configuration from "
                            + path,
                    e
            );
        }
    }

    private JsonNode resolveEnvironmentVariables(JsonNode source)
    {
        if (source.isObject()) {
            ObjectNode resolved = mapper.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields =
                    source.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                resolved.set(
                        field.getKey(),
                        resolveEnvironmentVariables(field.getValue())
                );
            }
            return resolved;
        }
        if (source.isArray()) {
            ArrayNode resolved = mapper.createArrayNode();
            for (JsonNode value : source) {
                resolved.add(resolveEnvironmentVariables(value));
            }
            return resolved;
        }
        if (source.isTextual()) {
            return TextNode.valueOf(
                    substituteEnvironmentVariables(source.textValue())
            );
        }
        return source;
    }

    private String substituteEnvironmentVariables(String value)
    {
        Matcher matcher = ENVIRONMENT_VARIABLE.matcher(value);
        StringBuilder resolved = new StringBuilder();
        while (matcher.find()) {
            String variable = matcher.group(1);
            String replacement = environment.apply(variable);
            if (replacement == null) {
                throw new IllegalArgumentException(
                        "PACT configuration references unset environment "
                                + "variable '" + variable + "'"
                );
            }
            matcher.appendReplacement(
                    resolved,
                    Matcher.quoteReplacement(replacement)
            );
        }
        matcher.appendTail(resolved);
        return resolved.toString();
    }
}
