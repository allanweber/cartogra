package io.cartogra.contract.domain;

import io.cartogra.contract.domain.CompatibilityChange.Direction;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;

class BreakingChangeEngineTest {

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final BreakingChangeEngine engine = new BreakingChangeEngine();

    @Test
    void firstVersionAddsEveryPathAndIsCompatible() {
        Compatibility result = engine.compare(null, tree("""
                {"openapi":"3.0.3","paths":{"/a":{"get":{}},"/b":{"post":{}}}}"""));

        assertThat(result.isBreaking()).isFalse();
        assertThat(result.changes()).extracting(CompatibilityChange::rule, CompatibilityChange::path)
                .containsExactly(tuple(CompatibilityRule.PATH_ADDED, "/a"), tuple(CompatibilityRule.PATH_ADDED, "/b"));
    }

    @Test
    void parsesCanonicalTextAndAcceptsANullFrom() {
        Compatibility result = engine.compare((String) null, "{\"openapi\":\"3.0.3\",\"paths\":{}}");
        assertThat(result.isBreaking()).isFalse();
        assertThat(result.changes()).isEmpty();
    }

    @Test
    void resolvesAnEscapedPointer() {
        String spec = """
                {"paths":{"/a":{"get":{"responses":{"200":{"content":{"application/json":{"schema":
                  {"$ref":"#/components/schemas/a~1b~0c"}}}}}}}},
                 "components":{"schemas":{"a/b~c":{"type":"object","properties":%s}}}}""";

        Compatibility result = engine.compare(tree(spec.formatted("{\"id\":{},\"name\":{}}")),
                tree(spec.formatted("{\"id\":{}}")));

        assertThat(result.changes()).extracting(CompatibilityChange::rule, CompatibilityChange::location)
                .containsExactly(tuple(CompatibilityRule.RESPONSE_FIELD_REMOVED, "responses.200.name"));
    }

    @Test
    void comparesExternalRefsByTheirRefString() {
        String spec = """
                {"paths":{"/a":{"post":{"requestBody":{"content":{"application/json":{"schema":
                  {"$ref":"%s"}}}}}}}}""";
        JsonNode same = tree(spec.formatted("common.yaml#/Payment"));

        assertThat(engine.compare(same, same.deepCopy()).changes()).isEmpty();
        Compatibility changed = engine.compare(same, tree(spec.formatted("common.yaml#/Refund")));
        assertThat(changed.changes()).extracting(CompatibilityChange::rule, CompatibilityChange::direction,
                        CompatibilityChange::location)
                .containsExactly(tuple(CompatibilityRule.REQUEST_FIELD_TYPE_CHANGED, Direction.REQUEST, "requestBody"));
        assertThat(changed.isBreaking()).isTrue();
    }

    @Test
    void aMissingRefDoesNotCrash() {
        String spec = """
                {"paths":{"/a":{"get":{"parameters":[{"$ref":"#/components/parameters/Gone"}],
                  "responses":{"200":{"content":{"application/json":{"schema":{"$ref":"#/components/schemas/%s"}}}}}}}}}""";

        assertThat(engine.compare(tree(spec.formatted("Gone")),
                tree(spec.formatted("Gone"))).changes()).isEmpty();
        assertThat(engine.compare(tree(spec.formatted("Gone")),
                tree(spec.formatted("Lost"))).changes())
                .extracting(CompatibilityChange::rule).containsExactly(CompatibilityRule.RESPONSE_FIELD_TYPE_CHANGED);
    }

    @Test
    void aRefToItselfEnds() {
        String spec = """
                {"paths":{"/a":{"get":{"responses":{"200":{"content":{"application/json":{"schema":
                  {"$ref":"#/components/schemas/Loop"}}}}}}}},
                 "components":{"schemas":{"Loop":{"$ref":"#/components/schemas/Loop"}}}}""";

        assertThat(engine.compare(tree(spec), tree(spec)).changes()).isEmpty();
    }

    private static final String ALL_OF_SPEC = """
            {"paths":{"/a":{"get":{"responses":{"200":{"content":{"application/json":{"schema":
              {"allOf":[{"$ref":"%s"},{"type":"object","properties":{"id":{"type":"string"}}}]}}}}}}}}}""";

    @Test
    void anOpaqueRefInAllOfIsComparedByItsRefString() {
        Compatibility changed = engine.compare(tree(ALL_OF_SPEC.formatted("ext.yaml#/A")),
                tree(ALL_OF_SPEC.formatted("ext.yaml#/B")));

        assertThat(changed.isBreaking()).isTrue();
        assertThat(changed.changes()).extracting(CompatibilityChange::rule, CompatibilityChange::direction,
                        CompatibilityChange::location)
                .containsExactly(tuple(CompatibilityRule.RESPONSE_FIELD_TYPE_CHANGED, Direction.RESPONSE,
                        "responses.200"));
    }

    @Test
    void theSameOpaqueRefInAllOfIsCompatible() {
        JsonNode spec = tree(ALL_OF_SPEC.formatted("ext.yaml#/A"));

        assertThat(engine.compare(spec, spec.deepCopy()).changes()).isEmpty();
    }

    @Test
    void aMissingLocalRefInAllOfDoesNotCrash() {
        JsonNode gone = tree(ALL_OF_SPEC.formatted("#/components/schemas/Gone"));

        assertThat(engine.compare(gone, gone.deepCopy()).changes()).isEmpty();
        assertThat(engine.compare(gone, tree(ALL_OF_SPEC.formatted("#/components/schemas/Lost"))).changes())
                .extracting(CompatibilityChange::rule).containsExactly(CompatibilityRule.RESPONSE_FIELD_TYPE_CHANGED);
    }

    @Test
    void operationParameterOverridesThePathParameter() {
        String spec = """
                {"paths":{"/a":{"parameters":[{"name":"q","in":"query","required":true,"schema":{"type":"string"}}],
                  "get":{"parameters":%s}}}}""";
        JsonNode before = tree(spec.formatted("[]"));
        JsonNode after = tree(spec.formatted(
                "[{\"name\":\"q\",\"in\":\"query\",\"required\":false,\"schema\":{\"type\":\"string\"}}]"));

        assertThat(engine.compare(before, after).changes()).extracting(CompatibilityChange::rule, CompatibilityChange::location)
                .containsExactly(tuple(CompatibilityRule.PARAMETER_REQUIRED_REMOVED, "query.q"));
    }

    @Test
    void aParameterIsKeyedByNameAndLocation() {
        String spec = """
                {"paths":{"/a":{"get":{"parameters":[{"name":"id","in":"%s","schema":{"type":"string"}}]}}}}""";

        assertThat(engine.compare(tree(spec.formatted("query")),
                tree(spec.formatted("header"))).changes())
                .extracting(CompatibilityChange::rule, CompatibilityChange::location)
                .containsExactly(tuple(CompatibilityRule.PARAMETER_OPTIONAL_ADDED, "header.id"));
    }

    @Test
    void aTypeChangeToACompositionIsATypeChange() {
        String spec = """
                {"paths":{"/a":{"get":{"responses":{"200":{"content":{"application/json":{"schema":%s}}}}}}}}""";

        assertThat(engine.compare(tree(spec.formatted("{\"type\":\"string\"}")),
                tree(spec.formatted("{\"oneOf\":[{\"type\":\"string\"},{\"type\":\"integer\"}]}")))
                .changes()).extracting(CompatibilityChange::rule)
                .containsExactly(CompatibilityRule.RESPONSE_FIELD_TYPE_CHANGED);
    }

    @Test
    void aSharedSchemaChainIsComparedOncePerOperationAndDirection() {
        int depth = 40;
        JsonNode before = tree(chainSpec(depth, false));
        JsonNode after = tree(chainSpec(depth, true));

        Compatibility result = assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> engine.compare(before, after));

        assertThat(result.isBreaking()).isTrue();
        assertThat(result.changes()).extracting(CompatibilityChange::rule, CompatibilityChange::location)
                .containsExactly(tuple(CompatibilityRule.RESPONSE_FIELD_REMOVED,
                        "responses.200" + ".a".repeat(depth) + ".leaf"));
    }

    private static final String TWO_ADDRESSES = """
            {"paths":{"/o":{"get":{"responses":{"200":{"content":{"application/json":{"schema":
              {"type":"object","properties":{"billing":%s,"shipping":%s}}}}}}}}},
             "components":{"schemas":{"Addr":%s}}}""";
    private static final String ADDR_REF = "{\"$ref\":\"#/components/schemas/Addr\"}";
    private static final String STREET = "{\"type\":\"object\",\"properties\":{\"street\":{\"type\":\"string\"}}}";
    private static final String STREET_COUNTRY = """
            {"type":"object","properties":{"street":{"type":"string"},"country":{"type":"string"}}}""";

    @Test
    void twoInlineSchemasMovedToOneSharedRefAreEachCompared() {
        JsonNode before = tree(TWO_ADDRESSES.formatted(STREET, STREET_COUNTRY, "{}"));
        JsonNode after = tree(TWO_ADDRESSES.formatted(ADDR_REF, ADDR_REF, STREET));

        Compatibility result = engine.compare(before, after);

        assertThat(result.isBreaking()).isTrue();
        assertThat(result.changes()).extracting(CompatibilityChange::rule, CompatibilityChange::direction,
                        CompatibilityChange::location)
                .containsExactly(tuple(CompatibilityRule.RESPONSE_FIELD_REMOVED, Direction.RESPONSE,
                        "responses.200.shipping.country"));
    }

    @Test
    void oneSharedRefSplitIntoTwoInlineSchemasComparesEach() {
        JsonNode before = tree(TWO_ADDRESSES.formatted(ADDR_REF, ADDR_REF, STREET_COUNTRY));
        JsonNode after = tree(TWO_ADDRESSES.formatted(STREET_COUNTRY, STREET, "{}"));

        Compatibility result = engine.compare(before, after);

        assertThat(result.isBreaking()).isTrue();
        assertThat(result.changes()).extracting(CompatibilityChange::rule, CompatibilityChange::direction,
                        CompatibilityChange::location)
                .containsExactly(tuple(CompatibilityRule.RESPONSE_FIELD_REMOVED, Direction.RESPONSE,
                        "responses.200.shipping.country"));
    }

    @Test
    void anInlineNestingComparedWithARecursiveRefIsWalkedToTheEnd() {
        String before = """
                {"paths":{"/a":{"get":{"responses":{"200":{"content":{"application/json":{"schema":
                  {"$ref":"#/components/schemas/Node"}}}}}}}},
                 "components":{"schemas":{"Node":{"type":"object","properties":{
                   "name":{"type":"string"},"child":{"$ref":"#/components/schemas/Node"}}}}}}""";
        String after = """
                {"paths":{"/a":{"get":{"responses":{"200":{"content":{"application/json":{"schema":
                  {"type":"object","properties":{"name":{"type":"string"},
                    "child":{"type":"object","properties":{"child":{"type":"object"}}}}}}}}}}}}}""";

        Compatibility result = engine.compare(tree(before), tree(after));

        assertThat(result.changes()).extracting(CompatibilityChange::rule, CompatibilityChange::location)
                .containsExactlyInAnyOrder(
                        tuple(CompatibilityRule.RESPONSE_FIELD_REMOVED, "responses.200.child.name"),
                        tuple(CompatibilityRule.RESPONSE_FIELD_REMOVED, "responses.200.child.child.name"),
                        tuple(CompatibilityRule.RESPONSE_FIELD_REMOVED, "responses.200.child.child.child"));
    }

    private static String chainSpec(int depth, boolean leafRemoved) {
        StringBuilder schemas = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            String next = "{\"$ref\":\"#/components/schemas/S" + (i + 1) + "\"}";
            schemas.append("\"S").append(i).append("\":{\"type\":\"object\",\"properties\":{\"a\":")
                    .append(next).append(",\"b\":").append(next).append("}},");
        }
        schemas.append("\"S").append(depth).append("\":{\"type\":\"object\",\"properties\":")
                .append(leafRemoved ? "{\"id\":{}}" : "{\"id\":{},\"leaf\":{}}").append("}");
        return """
                {"paths":{"/a":{"get":{"responses":{"200":{"content":{"application/json":{"schema":
                  {"$ref":"#/components/schemas/S0"}}}}}}}},
                 "components":{"schemas":{%s}}}""".formatted(schemas);
    }

    @Test
    void ruleIdsAreKebabCase() {
        assertThat(CompatibilityRule.REQUEST_FIELD_REQUIRED_ADDED.id()).isEqualTo("request-field-required-added");
    }

    private static JsonNode tree(String json) {
        return JSON.readTree(json);
    }
}
