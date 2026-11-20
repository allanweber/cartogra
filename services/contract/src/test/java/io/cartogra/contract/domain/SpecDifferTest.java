package io.cartogra.contract.domain;

import io.cartogra.contract.domain.DiffLine.Kind;
import io.cartogra.contract.domain.SpecChange.Op;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class SpecDifferTest {

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final SpecDiffer differ = new SpecDiffer(new BreakingChangeEngine());
    private final SpecParser parser = new SpecParser();

    @Test
    void identicalTreesHaveNoChanges() {
        JsonNode tree = tree("{\"a\":{\"b\":[1,2]}}");
        assertThat(differ.changes(tree, tree.deepCopy())).isEmpty();
    }

    @Test
    void reportsAddedRemovedAndChangedKeysWithPointers() {
        List<SpecChange> changes = differ.changes(
                tree("{\"info\":{\"version\":\"1.0.0\"},\"gone\":true,\"paths\":{}}"),
                tree("{\"info\":{\"version\":\"1.1.0\"},\"paths\":{\"/a/{id}\":{\"get\":{}}}}"));

        assertThat(changes).extracting(SpecChange::op, SpecChange::pointer).containsExactly(
                tuple(Op.REMOVED, "/gone"),
                tuple(Op.CHANGED, "/info/version"),
                tuple(Op.ADDED, "/paths/~1a~1{id}"));
        assertThat(changes.get(1).before().asString()).isEqualTo("1.0.0");
        assertThat(changes.get(1).after().asString()).isEqualTo("1.1.0");
        assertThat(changes.get(2).after()).isEqualTo(tree("{\"get\":{}}"));
    }

    @Test
    void escapesTildeBeforeSlash() {
        List<SpecChange> changes = differ.changes(tree("{}"), tree("{\"a~/b\":1}"));
        assertThat(changes).extracting(SpecChange::pointer).containsExactly("/a~0~1b");
        assertThat(changes.getFirst().segments()).containsExactly("a~/b");
    }

    @Test
    void comparesArraysByIndex() {
        List<SpecChange> changes = differ.changes(tree("{\"t\":[\"a\",\"b\"]}"), tree("{\"t\":[\"a\",\"c\",\"d\"]}"));
        assertThat(changes).extracting(SpecChange::op, SpecChange::pointer).containsExactly(
                tuple(Op.CHANGED, "/t/1"),
                tuple(Op.ADDED, "/t/2"));
    }

    @Test
    void typeChangeIsOneChange() {
        List<SpecChange> changes = differ.changes(tree("{\"a\":{\"b\":1}}"), tree("{\"a\":[1]}"));
        assertThat(changes).extracting(SpecChange::op, SpecChange::pointer).containsExactly(tuple(Op.CHANGED, "/a"));
    }

    @Test
    void firstVersionReportsEveryTopLevelKeyAsAdded() {
        List<SpecChange> changes = differ.changes(null, tree("{\"info\":{},\"openapi\":\"3.0.3\"}"));
        assertThat(changes).extracting(SpecChange::op, SpecChange::pointer)
                .containsExactly(tuple(Op.ADDED, "/info"), tuple(Op.ADDED, "/openapi"));
    }

    @Test
    void alignsChangedAddedAndRemovedLines() {
        List<DiffLine> lines = differ.lines("a\nb\nc\nd\n", "a\nB\nc\nd\ne\n");

        assertThat(lines).extracting(DiffLine::kind, DiffLine::leftNo, DiffLine::left, DiffLine::rightNo,
                DiffLine::right).containsExactly(
                tuple(Kind.SAME, 1, "a", 1, "a"),
                tuple(Kind.CHANGED, 2, "b", 2, "B"),
                tuple(Kind.SAME, 3, "c", 3, "c"),
                tuple(Kind.SAME, 4, "d", 4, "d"),
                tuple(Kind.ADDED, null, null, 5, "e"));
    }

    @Test
    void unpairedLinesOfAChangeBlockAreRemoved() {
        List<DiffLine> lines = differ.lines("x\ny\nz\n", "q\n");
        assertThat(lines).extracting(DiffLine::kind).containsExactly(Kind.CHANGED, Kind.REMOVED, Kind.REMOVED);
    }

    @Test
    void firstVersionDiffIsAllAddedAgainstNothing() {
        VersionDiff diff = differ.diff(null, version(1, SpecParserTest.YAML_30));

        assertThat(diff.from()).isNull();
        assertThat(diff.lines()).isNotEmpty().allMatch(l -> l.kind() == Kind.ADDED && l.left() == null);
    }

    @Test
    void reformattedSpecsDiffOnlyOnRealChanges() {
        String v1 = """
                openapi: 3.0.3
                info: {title: Ledger, version: 1.0.0}
                paths:
                  /accounts:
                    get:
                      responses: {'200': {description: OK}}
                """;
        String v2 = """
                {"paths": {"/accounts": {"get": {"responses": {"200": {"description": "OK"}}}},
                           "/accounts/{id}": {"get": {"parameters": [{"name": "id", "in": "path", "required": true,
                             "schema": {"type": "string"}}], "responses": {"200": {"description": "OK"}}}}},
                 "info": {"version": "1.0.0", "title": "Ledger"}, "openapi": "3.0.3"}
                """;

        VersionDiff diff = differ.diff(version(1, v1), version(2, v2));

        assertThat(diff.changes()).extracting(SpecChange::op, SpecChange::pointer)
                .containsExactly(tuple(Op.ADDED, "/paths/~1accounts~1{id}"));
        assertThat(diff.lines()).filteredOn(l -> l.kind() != Kind.SAME)
                .allMatch(l -> l.kind() == Kind.ADDED);
        assertThat(diff.lines()).filteredOn(l -> l.kind() == Kind.ADDED)
                .extracting(DiffLine::right).anyMatch(r -> r.contains("/accounts/{id}"));
        assertThat(diff.compatibility().isBreaking()).isFalse();
        assertThat(diff.compatibility().changes()).extracting(CompatibilityChange::rule)
                .containsExactly(CompatibilityRule.PATH_ADDED);
    }

    private ContractVersion version(int number, String spec) {
        ParsedSpec parsed = parser.parse(spec);
        return new ContractVersion(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), number, parsed.label(),
                spec, parsed.canonical(), parsed.hash(), null, Instant.now(), ContractVersion.SOURCE_MANUAL, null, null,
                null);
    }

    private static JsonNode tree(String json) {
        return JSON.readTree(json);
    }
}
