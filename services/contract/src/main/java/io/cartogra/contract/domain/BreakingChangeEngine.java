package io.cartogra.contract.domain;

import io.cartogra.contract.domain.CompatibilityChange.Direction;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Classifies the changes between two OpenAPI specs as breaking or compatible for a consumer. It compares operation by
 * operation: parameters and request bodies in the request direction, responses in the response direction. The engine
 * resolves local {@code $ref}s at each use site and judges a shared schema once per operation and {@link Mode}. It
 * compares an external or unresolvable ref by its ref string, also inside {@code allOf}. {@code allOf} is merged;
 * {@code oneOf}/{@code anyOf} are not walked.
 */
@Component
public class BreakingChangeEngine {

    private static final List<String> METHODS = List.of("get", "put", "post", "delete", "options", "head", "patch",
            "trace");
    private static final Pattern TEMPLATE_PARAM = Pattern.compile("\\{([^}/]*)}");
    private static final int MAX_REF_CHAIN = 32;

    private final ObjectMapper json = JsonMapper.builder().build();

    public Compatibility compare(@Nullable String fromCanonical, String toCanonical) {
        return compare(fromCanonical == null ? null : json.readTree(fromCanonical), json.readTree(toCanonical));
    }

    public Compatibility compare(@Nullable JsonNode from, JsonNode to) {
        JsonNode oldRoot = from == null ? JsonNodeFactory.instance.objectNode() : from;
        Set<CompatibilityChange> out = new LinkedHashSet<>();
        Map<String, PathEntry> oldPaths = paths(oldRoot);
        Map<String, PathEntry> newPaths = paths(to);
        Document oldDoc = Document.of(oldRoot);
        Document newDoc = Document.of(to);
        TreeSet<String> keys = new TreeSet<>(oldPaths.keySet());
        keys.addAll(newPaths.keySet());
        for (String key : keys) {
            PathEntry o = oldPaths.get(key);
            PathEntry n = newPaths.get(key);
            if (n == null) {
                out.add(new CompatibilityChange(CompatibilityRule.PATH_REMOVED, Direction.OPERATION, null,
                        o.template(), null, "Path " + o.template() + " was removed"));
            } else if (o == null) {
                out.add(new CompatibilityChange(CompatibilityRule.PATH_ADDED, Direction.OPERATION, null,
                        n.template(), null, "Path " + n.template() + " was added"));
            } else {
                comparePath(oldDoc, newDoc, o, n, out);
            }
        }
        return Compatibility.of(new ArrayList<>(out));
    }

    private void comparePath(Document oldDoc, Document newDoc, PathEntry o, PathEntry n,
                             Set<CompatibilityChange> out) {
        for (String method : METHODS) {
            JsonNode oldOp = o.item().get(method);
            JsonNode newOp = n.item().get(method);
            boolean hadOp = oldOp != null && oldOp.isObject();
            boolean hasOp = newOp != null && newOp.isObject();
            String verb = method.toUpperCase(Locale.ROOT);
            if (hadOp && !hasOp) {
                out.add(new CompatibilityChange(CompatibilityRule.OPERATION_REMOVED, Direction.OPERATION, verb,
                        n.template(), null, "Operation " + verb + " " + n.template() + " was removed"));
            } else if (!hadOp && hasOp) {
                out.add(new CompatibilityChange(CompatibilityRule.OPERATION_ADDED, Direction.OPERATION, verb,
                        n.template(), null, "Operation " + verb + " " + n.template() + " was added"));
            } else if (hadOp) {
                new Operation(oldDoc, newDoc, verb, n.template(), out).compare(o, oldOp, n, newOp);
            }
        }
    }

    /** Paths keyed by their template with the parameter names removed, so {@code /a/{id}} matches {@code /a/{x}}. */
    private static Map<String, PathEntry> paths(JsonNode root) {
        Map<String, PathEntry> out = new TreeMap<>();
        JsonNode paths = root.get("paths");
        if (paths == null || !paths.isObject()) {
            return out;
        }
        for (Map.Entry<String, JsonNode> e : paths.properties()) {
            JsonNode item = derefObject(root, e.getValue());
            if (item != null) {
                out.putIfAbsent(TEMPLATE_PARAM.matcher(e.getKey()).replaceAll("{}"), new PathEntry(e.getKey(), item));
            }
        }
        return out;
    }

    private record PathEntry(String template, JsonNode item) {

        List<String> templateParams() {
            List<String> names = new ArrayList<>();
            Matcher m = TEMPLATE_PARAM.matcher(template);
            while (m.find()) {
                names.add(m.group(1));
            }
            return names;
        }
    }

    /**
     * A spec with the JSON pointer of each object and array node in it. A schema side is identified by the pointer of
     * its node: a shared component has one pointer, two inline schemas have two.
     */
    private record Document(JsonNode root, IdentityHashMap<JsonNode, String> pointers) {

        static Document of(JsonNode root) {
            IdentityHashMap<JsonNode, String> pointers = new IdentityHashMap<>();
            index(root, "", pointers);
            return new Document(root, pointers);
        }

        private static void index(JsonNode node, String pointer, Map<JsonNode, String> pointers) {
            if (!node.isContainer() || pointers.putIfAbsent(node, pointer) != null) {
                return;
            }
            if (node.isObject()) {
                for (Map.Entry<String, JsonNode> e : node.properties()) {
                    index(e.getValue(), pointer + "/" + e.getKey().replace("~", "~0").replace("/", "~1"), pointers);
                }
            } else {
                for (int i = 0; i < node.size(); i++) {
                    index(node.get(i), pointer + "/" + i, pointers);
                }
            }
        }

        @Nullable String pointerOf(JsonNode node) {
            return pointers.get(node);
        }
    }

    /** The pair of schema nodes in one comparison, each by its JSON pointer in its own spec. */
    private record SchemaPair(String before, String after) {}

    private record ComparedPair(Mode mode, SchemaPair pair) {}

    /** One operation present in both specs; collects its changes into {@code out}. */
    private static final class Operation {

        private final Document oldDoc;
        private final Document newDoc;
        private final String method;
        private final String path;
        private final Set<CompatibilityChange> out;
        private final Set<ComparedPair> compared = new HashSet<>();

        Operation(Document oldDoc, Document newDoc, String method, String path, Set<CompatibilityChange> out) {
            this.oldDoc = oldDoc;
            this.newDoc = newDoc;
            this.method = method;
            this.path = path;
            this.out = out;
        }

        void compare(PathEntry oldPath, JsonNode oldOp, PathEntry newPath, JsonNode newOp) {
            compareParameters(parameters(oldDoc.root(), oldPath, oldOp), parameters(newDoc.root(), newPath, newOp));
            compareRequestBody(derefObject(oldDoc.root(), oldOp.get("requestBody")),
                    derefObject(newDoc.root(), newOp.get("requestBody")));
            compareResponses(oldOp.get("responses"), newOp.get("responses"));
        }

        private void compareParameters(Map<String, JsonNode> before, Map<String, JsonNode> after) {
            for (Map.Entry<String, JsonNode> e : after.entrySet()) {
                JsonNode n = e.getValue();
                JsonNode o = before.get(e.getKey());
                String location = n.path("in").asString() + "." + n.path("name").asString();
                boolean nowRequired = n.path("required").asBoolean(false);
                if (o == null) {
                    add(nowRequired ? CompatibilityRule.PARAMETER_REQUIRED_ADDED
                                    : CompatibilityRule.PARAMETER_OPTIONAL_ADDED, Direction.REQUEST, location,
                            (nowRequired ? "Required" : "Optional") + " parameter " + location + " was added");
                    continue;
                }
                boolean wasRequired = o.path("required").asBoolean(false);
                if (!wasRequired && nowRequired) {
                    add(CompatibilityRule.PARAMETER_REQUIRED_ADDED, Direction.REQUEST, location,
                            "Parameter " + location + " is now required");
                } else if (wasRequired && !nowRequired) {
                    add(CompatibilityRule.PARAMETER_REQUIRED_REMOVED, Direction.REQUEST, location,
                            "Parameter " + location + " is no longer required");
                }
                JsonNode os = parameterSchema(o);
                JsonNode ns = parameterSchema(n);
                if (os != null && ns != null) {
                    compareSchema(Mode.PARAMETER, location, os, ns, new HashSet<>());
                }
            }
        }

        private void compareRequestBody(@Nullable JsonNode before, @Nullable JsonNode after) {
            if (before == null || after == null) {
                return;
            }
            compareContent(Mode.REQUEST, "requestBody", before.get("content"), after.get("content"));
        }

        private void compareResponses(@Nullable JsonNode before, @Nullable JsonNode after) {
            if (before == null || after == null || !before.isObject() || !after.isObject()) {
                return;
            }
            for (String code : new TreeSet<>(before.propertyNames())) {
                JsonNode o = derefObject(oldDoc.root(), before.get(code));
                JsonNode n = derefObject(newDoc.root(), after.get(code));
                if (o != null && n != null) {
                    compareContent(Mode.RESPONSE, "responses." + code, o.get("content"), n.get("content"));
                }
            }
        }

        private void compareContent(Mode mode, String location, @Nullable JsonNode before, @Nullable JsonNode after) {
            if (before == null || after == null || !before.isObject() || !after.isObject()) {
                return;
            }
            for (String mediaType : new TreeSet<>(before.propertyNames())) {
                JsonNode os = before.get(mediaType).get("schema");
                JsonNode n = after.get(mediaType);
                JsonNode ns = n == null ? null : n.get("schema");
                if (os != null && ns != null) {
                    compareSchema(mode, location, os, ns, new HashSet<>());
                }
            }
        }

        private void compareSchema(Mode mode, String location, JsonNode before, JsonNode after,
                                   Set<SchemaPair> onStack) {
            Resolved o = derefSchema(oldDoc.root(), before);
            Resolved n = derefSchema(newDoc.root(), after);
            if (o.opaque() || n.opaque()) {
                if (!(o.opaque() && n.opaque() && o.ref().equals(n.ref()))) {
                    typeChanged(mode, location, describe(o), describe(n));
                }
                return;
            }
            @Nullable String oldPointer = oldDoc.pointerOf(o.node());
            @Nullable String newPointer = newDoc.pointerOf(n.node());
            @Nullable SchemaPair pair = oldPointer == null || newPointer == null ? null
                    : new SchemaPair(oldPointer, newPointer);
            if (pair != null) {
                if (!onStack.add(pair)) {
                    return;
                }
                if (!compared.add(new ComparedPair(mode, pair))) {
                    onStack.remove(pair);
                    return;
                }
            }
            Shape os = Shape.of(oldDoc.root(), o.node(), new HashSet<>());
            Shape ns = Shape.of(newDoc.root(), n.node(), new HashSet<>());
            try {
                if (os.type() != null && ns.type() != null && !os.type().equals(ns.type())) {
                    typeChanged(mode, location, os.type(), ns.type());
                    return;
                }
                if (!os.opaqueRefs().equals(ns.opaqueRefs())) {
                    typeChanged(mode, location, describeAllOf(os.opaqueRefs()), describeAllOf(ns.opaqueRefs()));
                }
                compareEnums(mode, location, os.enumValues(), ns.enumValues());
                if (mode == Mode.PARAMETER) {
                    return;
                }
                compareProperties(mode, location, os, ns, onStack);
                if (os.items() != null && ns.items() != null) {
                    compareSchema(mode, location + "[]", os.items(), ns.items(), onStack);
                }
            } finally {
                if (pair != null) {
                    onStack.remove(pair);
                }
            }
        }

        private void compareProperties(Mode mode, String location, Shape before, Shape after,
                                       Set<SchemaPair> onStack) {
            TreeSet<String> names = new TreeSet<>(before.properties().keySet());
            names.addAll(after.properties().keySet());
            for (String name : names) {
                JsonNode o = before.properties().get(name);
                JsonNode n = after.properties().get(name);
                String field = location + "." + name;
                boolean wasRequired = before.required().contains(name);
                boolean nowRequired = after.required().contains(name);
                if (n == null) {
                    if (mode == Mode.REQUEST) {
                        add(CompatibilityRule.REQUEST_FIELD_REMOVED, Direction.REQUEST, field,
                                "Request field " + field + " was removed");
                    } else {
                        add(CompatibilityRule.RESPONSE_FIELD_REMOVED, Direction.RESPONSE, field,
                                "Response field " + field + " was removed");
                    }
                } else if (o == null) {
                    if (mode == Mode.RESPONSE) {
                        add(CompatibilityRule.RESPONSE_FIELD_ADDED, Direction.RESPONSE, field,
                                "Response field " + field + " was added");
                    } else if (nowRequired) {
                        add(CompatibilityRule.REQUEST_FIELD_REQUIRED_ADDED, Direction.REQUEST, field,
                                "Required request field " + field + " was added");
                    } else {
                        add(CompatibilityRule.REQUEST_FIELD_OPTIONAL_ADDED, Direction.REQUEST, field,
                                "Optional request field " + field + " was added");
                    }
                } else {
                    if (mode == Mode.REQUEST && !wasRequired && nowRequired) {
                        add(CompatibilityRule.REQUEST_FIELD_REQUIRED_ADDED, Direction.REQUEST, field,
                                "Request field " + field + " is now required");
                    } else if (mode == Mode.REQUEST && wasRequired && !nowRequired) {
                        add(CompatibilityRule.REQUEST_FIELD_REQUIRED_REMOVED, Direction.REQUEST, field,
                                "Request field " + field + " is no longer required");
                    }
                    compareSchema(mode, field, o, n, onStack);
                }
            }
        }

        private void compareEnums(Mode mode, String location, @Nullable List<JsonNode> before,
                                  @Nullable List<JsonNode> after) {
            if (before == null || after == null) {
                return;
            }
            List<String> removed = before.stream().filter(v -> !after.contains(v)).map(JsonNode::toString).toList();
            List<String> added = after.stream().filter(v -> !before.contains(v)).map(JsonNode::toString).toList();
            if (!removed.isEmpty()) {
                add(mode.enumRemoved, mode.direction, location,
                        mode.label + " " + location + " no longer allows " + String.join(", ", removed));
            }
            if (!added.isEmpty()) {
                add(mode.enumAdded, mode.direction, location,
                        mode.label + " " + location + " now allows " + String.join(", ", added));
            }
        }

        private void typeChanged(Mode mode, String location, @Nullable String before, @Nullable String after) {
            add(mode.typeChanged, mode.direction, location,
                    mode.label + " " + location + " changed type from " + before + " to " + after);
        }

        private void add(CompatibilityRule rule, Direction direction, String location, String message) {
            out.add(new CompatibilityChange(rule, direction, method, path, location, message));
        }

        private static String describeAllOf(Set<String> refs) {
            return refs.isEmpty() ? "an allOf with no external reference" : "an allOf of " + String.join(", ", refs);
        }

        private static String describe(Resolved r) {
            return r.ref() == null ? "an inline schema" : "a reference to " + r.ref();
        }
    }

    private enum Mode {
        REQUEST(Direction.REQUEST, "Request field", CompatibilityRule.REQUEST_FIELD_TYPE_CHANGED,
                CompatibilityRule.REQUEST_ENUM_VALUE_REMOVED, CompatibilityRule.REQUEST_ENUM_VALUE_ADDED),
        RESPONSE(Direction.RESPONSE, "Response field", CompatibilityRule.RESPONSE_FIELD_TYPE_CHANGED,
                CompatibilityRule.RESPONSE_ENUM_VALUE_REMOVED, CompatibilityRule.RESPONSE_ENUM_VALUE_ADDED),
        PARAMETER(Direction.REQUEST, "Parameter", CompatibilityRule.PARAMETER_TYPE_CHANGED,
                CompatibilityRule.PARAMETER_ENUM_VALUE_REMOVED, CompatibilityRule.PARAMETER_ENUM_VALUE_ADDED);

        final Direction direction;
        final String label;
        final CompatibilityRule typeChanged;
        final CompatibilityRule enumRemoved;
        final CompatibilityRule enumAdded;

        Mode(Direction direction, String label, CompatibilityRule typeChanged, CompatibilityRule enumRemoved,
             CompatibilityRule enumAdded) {
            this.direction = direction;
            this.label = label;
            this.typeChanged = typeChanged;
            this.enumRemoved = enumRemoved;
            this.enumAdded = enumAdded;
        }
    }

    /**
     * Merges path-level and operation-level parameters; the operation-level parameter replaces the path-level one. A
     * parameter is keyed by {@code (name, in)}; a path parameter by its position in the template, so a renamed
     * template parameter matches.
     */
    private static Map<String, JsonNode> parameters(JsonNode root, PathEntry path, JsonNode operation) {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        List<String> templateParams = path.templateParams();
        for (JsonNode list : new JsonNode[] {path.item().get("parameters"), operation.get("parameters")}) {
            if (list == null || !list.isArray()) {
                continue;
            }
            for (JsonNode p : list) {
                JsonNode param = derefObject(root, p);
                if (param == null || !param.path("name").isString() || !param.path("in").isString()) {
                    continue;
                }
                String name = param.get("name").stringValue();
                String in = param.get("in").stringValue();
                int position = "path".equals(in) ? templateParams.indexOf(name) : -1;
                out.put(position >= 0 ? "path#" + position : in + ":" + name, param);
            }
        }
        return out;
    }

    private static @Nullable JsonNode parameterSchema(JsonNode param) {
        JsonNode schema = param.get("schema");
        if (schema != null) {
            return schema;
        }
        JsonNode content = param.get("content");
        if (content != null && content.isObject() && !content.isEmpty()) {
            return content.properties().iterator().next().getValue().get("schema");
        }
        return null;
    }

    /** Follows a chain of local refs to an object (a parameter, request body, response or path item). */
    private static @Nullable JsonNode derefObject(JsonNode root, @Nullable JsonNode node) {
        if (node == null) {
            return null;
        }
        Resolved r = derefSchema(root, node);
        return r.opaque() || !r.node().isObject() ? null : r.node();
    }

    /** A schema after its local refs; {@code opaque} when a ref is external, missing or cyclic on itself. */
    private record Resolved(JsonNode node, @Nullable String ref, boolean opaque) {}

    private static Resolved derefSchema(JsonNode root, JsonNode node) {
        JsonNode current = node;
        String ref = null;
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < MAX_REF_CHAIN; i++) {
            JsonNode refNode = current.isObject() ? current.get("$ref") : null;
            if (refNode == null || !refNode.isString()) {
                return new Resolved(current, ref, false);
            }
            ref = refNode.stringValue();
            JsonNode target = seen.add(ref) ? local(root, ref) : null;
            if (target == null) {
                return new Resolved(current, ref, true);
            }
            current = target;
        }
        return new Resolved(current, ref, true);
    }

    private static @Nullable JsonNode local(JsonNode root, String ref) {
        if (!ref.startsWith("#")) {
            return null;
        }
        String pointer = ref.substring(1);
        if (!pointer.isEmpty() && !pointer.startsWith("/")) {
            return null;
        }
        try {
            JsonNode target = root.at(percentDecode(pointer));
            return target.isMissingNode() ? null : target;
        } catch (IllegalArgumentException _) {
            return null;
        }
    }

    private static String percentDecode(String s) {
        if (s.indexOf('%') < 0) {
            return s;
        }
        StringBuilder out = new StringBuilder();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length() && isHex(s.charAt(i + 1)) && isHex(s.charAt(i + 2))) {
                bytes.write(Integer.parseInt(s.substring(i + 1, i + 3), 16));
                i += 2;
            } else {
                if (bytes.size() > 0) {
                    out.append(bytes.toString(StandardCharsets.UTF_8));
                    bytes.reset();
                }
                out.append(c);
            }
        }
        if (bytes.size() > 0) {
            out.append(bytes.toString(StandardCharsets.UTF_8));
        }
        return out.toString();
    }

    private static boolean isHex(char c) {
        return Character.digit(c, 16) >= 0;
    }

    /**
     * A schema with its {@code allOf} parts merged: the union of properties and of required names. {@code opaqueRefs}
     * holds the ref strings of the external or missing {@code allOf} parts.
     */
    private record Shape(@Nullable String type, Map<String, JsonNode> properties, Set<String> required,
                         @Nullable JsonNode items, @Nullable List<JsonNode> enumValues, Set<String> opaqueRefs) {

        static Shape of(JsonNode root, JsonNode schema, Set<String> seenRefs) {
            String type = declaredType(schema);
            Map<String, JsonNode> properties = new LinkedHashMap<>();
            JsonNode props = schema.get("properties");
            if (props != null && props.isObject()) {
                props.properties().forEach(e -> properties.put(e.getKey(), e.getValue()));
            }
            Set<String> required = new HashSet<>();
            JsonNode req = schema.get("required");
            if (req != null && req.isArray()) {
                req.forEach(r -> required.add(r.asString()));
            }
            JsonNode items = schema.get("items");
            List<JsonNode> enumValues = null;
            JsonNode en = schema.get("enum");
            if (en != null && en.isArray()) {
                enumValues = new ArrayList<>();
                en.forEach(enumValues::add);
            }
            Set<String> opaqueRefs = new TreeSet<>();
            JsonNode allOf = schema.get("allOf");
            if (allOf != null && allOf.isArray()) {
                for (JsonNode part : allOf) {
                    Resolved r = derefSchema(root, part);
                    if (r.opaque()) {
                        opaqueRefs.add(r.ref());
                        continue;
                    }
                    if (r.ref() != null && !seenRefs.add(r.ref())) {
                        continue;
                    }
                    Shape p = of(root, r.node(), seenRefs);
                    opaqueRefs.addAll(p.opaqueRefs());
                    p.properties().forEach(properties::putIfAbsent);
                    required.addAll(p.required());
                    type = type == null ? p.type() : type;
                    items = items == null ? p.items() : items;
                    enumValues = enumValues == null ? p.enumValues() : enumValues;
                }
            }
            if (type == null) {
                type = schema.has("oneOf") ? "oneOf"
                        : schema.has("anyOf") ? "anyOf"
                        : !properties.isEmpty() ? "object"
                        : items != null ? "array"
                        : null;
            }
            return new Shape(type, properties, required, items, enumValues, opaqueRefs);
        }

        /** {@code type}, with {@code "null"} removed from a 3.1 type list: nullability is not compared. */
        private static @Nullable String declaredType(JsonNode schema) {
            JsonNode type = schema.get("type");
            if (type == null) {
                return null;
            }
            if (type.isString()) {
                return type.stringValue();
            }
            if (type.isArray()) {
                TreeSet<String> types = new TreeSet<>();
                type.forEach(t -> {
                    if (!"null".equals(t.asString())) {
                        types.add(t.asString());
                    }
                });
                return types.isEmpty() ? null : String.join("|", types);
            }
            return null;
        }
    }
}
