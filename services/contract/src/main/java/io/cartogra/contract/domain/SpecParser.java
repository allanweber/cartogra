package io.cartogra.contract.domain;

import io.cartogra.contract.domain.exception.InvalidSpecException;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Validates an OpenAPI 3.x spec (YAML or JSON) and reduces it to a canonical form whose hash is the same for any
 * key order, comments or serialization format. {@code $ref}s are never resolved, so parsing never fetches a URL.
 */
@Component
public class SpecParser {

    static final int MAX_MESSAGES = 50;

    private final ObjectMapper yaml = YAMLMapper.builder().build();
    private final ObjectMapper json = JsonMapper.builder().build();

    public ParsedSpec parse(String spec) {
        JsonNode tree = readTree(spec);
        requireOpenApi3(tree);
        validate(spec);
        String canonical = json.writeValueAsString(sorted(tree));
        return new ParsedSpec(canonical, sha256(canonical), label(tree));
    }

    private JsonNode readTree(String spec) {
        JsonNode tree;
        try {
            tree = yaml.readTree(spec);
        } catch (JacksonException e) {
            throw new InvalidSpecException(List.of("Spec is not valid YAML or JSON: " + e.getOriginalMessage()));
        }
        if (tree == null || !tree.isObject()) {
            throw new InvalidSpecException(List.of("Spec must be a YAML or JSON object"));
        }
        return tree;
    }

    private static void requireOpenApi3(JsonNode tree) {
        JsonNode version = tree.get("openapi");
        if (version == null || !version.isString() || !version.stringValue().startsWith("3.")) {
            String found = tree.has("swagger") ? "Swagger " + tree.get("swagger").asString()
                    : tree.has("asyncapi") ? "AsyncAPI " + tree.get("asyncapi").asString()
                    : "no 'openapi' field";
            throw new InvalidSpecException(List.of("Only OpenAPI 3.x specs are supported (found " + found + ")"));
        }
    }

    private static void validate(String spec) {
        ParseOptions options = new ParseOptions();
        options.setResolve(false);
        SwaggerParseResult result = new OpenAPIV3Parser().readContents(spec, null, options);
        List<String> messages = result.getMessages() == null ? List.of() : result.getMessages();
        if (result.getOpenAPI() == null || !messages.isEmpty()) {
            throw new InvalidSpecException(messages.isEmpty()
                    ? List.of("Spec could not be parsed as OpenAPI")
                    : messages.stream().limit(MAX_MESSAGES).toList());
        }
    }

    private static JsonNode sorted(JsonNode node) {
        if (node.isObject()) {
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            node.properties().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> out.set(e.getKey(), sorted(e.getValue())));
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            node.forEach(child -> out.add(sorted(child)));
            return out;
        }
        return node;
    }

    private static @Nullable String label(JsonNode tree) {
        JsonNode version = tree.path("info").path("version");
        return version.isValueNode() && !version.isNull() ? version.asString() : null;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
