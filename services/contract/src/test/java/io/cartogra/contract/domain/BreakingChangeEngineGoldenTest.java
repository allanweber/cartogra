package io.cartogra.contract.domain;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Each folder under {@code compat/} holds an old and a new spec and the changes the engine must classify. */
class BreakingChangeEngineGoldenTest {

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final SpecParser parser = new SpecParser();
    private final BreakingChangeEngine engine = new BreakingChangeEngine();

    static Stream<String> cases() throws IOException, URISyntaxException {
        Path root = Path.of(Objects.requireNonNull(
                BreakingChangeEngineGoldenTest.class.getResource("/compat")).toURI());
        try (Stream<Path> dirs = Files.list(root)) {
            return dirs.filter(Files::isDirectory).map(d -> d.getFileName().toString()).sorted().toList().stream();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void classifiesTheChanges(String name) throws IOException, URISyntaxException {
        Path dir = Path.of(Objects.requireNonNull(getClass().getResource("/compat/" + name)).toURI());
        String from = parser.parse(Files.readString(dir.resolve("old.yaml"))).canonical();
        String to = parser.parse(Files.readString(dir.resolve("new.yaml"))).canonical();
        JsonNode expected = JSON.readTree(Files.readString(dir.resolve("expected.json")));

        Compatibility actual = engine.compare(from, to);

        List<Map<String, Object>> expectedChanges = new ArrayList<>();
        expected.get("changes").forEach(c -> expectedChanges.add(row(
                c.get("rule").stringValue(), c.get("breaking").booleanValue(), c.get("direction").stringValue(),
                c.get("method").isNull() ? null : c.get("method").stringValue(), c.get("path").stringValue(),
                c.get("location").isNull() ? null : c.get("location").stringValue())));
        List<Map<String, Object>> actualChanges = actual.changes().stream().map(c -> row(c.rule().id(),
                c.breaking(), c.direction().name().toLowerCase(Locale.ROOT), c.method(), c.path(), c.location()))
                .toList();
        assertThat(actualChanges).containsExactlyInAnyOrderElementsOf(expectedChanges);
        assertThat(actual.isBreaking()).isEqualTo(expected.get("isBreaking").booleanValue());
        assertThat(actual.changes()).allSatisfy(c -> assertThat(c.message()).isNotBlank());
    }

    private static Map<String, Object> row(String rule, boolean breaking, String direction, String method,
                                           String path, String location) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("rule", rule);
        row.put("breaking", breaking);
        row.put("direction", direction);
        row.put("method", method);
        row.put("path", path);
        row.put("location", location);
        return row;
    }
}
