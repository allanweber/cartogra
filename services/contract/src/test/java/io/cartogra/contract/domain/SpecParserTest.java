package io.cartogra.contract.domain;

import io.cartogra.contract.domain.exception.InvalidSpecException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class SpecParserTest {

    static final String YAML_30 = """
            # payments API
            openapi: 3.0.3
            info:
              title: Payments
              version: 1.2.0
            paths:
              /payments:
                get:
                  tags: [a, b]
                  responses:
                    '200':
                      description: ok
            """;

    static final String JSON_30_REORDERED = """
            {
              "paths": {"/payments": {"get": {"responses": {"200": {"description": "ok"}}, "tags": ["a", "b"]}}},
              "info": {"version": "1.2.0", "title": "Payments"},
              "openapi": "3.0.3"
            }
            """;

    private final SpecParser parser = new SpecParser();

    @Test
    void yamlAndReorderedJsonHashTheSame() {
        ParsedSpec yaml = parser.parse(YAML_30);
        ParsedSpec json = parser.parse(JSON_30_REORDERED);

        assertThat(yaml.hash()).isEqualTo(json.hash()).matches("[0-9a-f]{64}");
        assertThat(yaml.canonical()).isEqualTo(json.canonical());
        assertThat(yaml.canonical()).startsWith("{\"info\":");
    }

    @Test
    void arrayOrderChangesTheHash() {
        String swapped = YAML_30.replace("tags: [a, b]", "tags: [b, a]");

        assertThat(parser.parse(swapped).hash()).isNotEqualTo(parser.parse(YAML_30).hash());
    }

    @Test
    void labelIsInfoVersion() {
        assertThat(parser.parse(YAML_30).label()).isEqualTo("1.2.0");
    }

    @Test
    void acceptsOpenApi31() {
        String spec = """
                openapi: 3.1.0
                info:
                  title: Ledger
                  version: '2'
                paths: {}
                """;

        assertThat(parser.parse(spec).label()).isEqualTo("2");
    }

    @Test
    void rejectsSwagger2() {
        String spec = """
                swagger: '2.0'
                info: {title: Old, version: '1'}
                paths: {}
                """;

        var ex = catchThrowableOfType(InvalidSpecException.class, () -> parser.parse(spec));

        assertThat(ex.messages()).singleElement().asString().contains("Swagger 2.0");
    }

    @Test
    void rejectsAsyncApi() {
        var ex = catchThrowableOfType(InvalidSpecException.class,
                () -> parser.parse("asyncapi: 2.6.0\ninfo: {title: x, version: '1'}\nchannels: {}\n"));

        assertThat(ex.messages()).singleElement().asString().contains("AsyncAPI");
    }

    @Test
    void rejectsGarbage() {
        var ex = catchThrowableOfType(InvalidSpecException.class, () -> parser.parse("openapi: [unclosed"));

        assertThat(ex.messages()).isNotEmpty();
    }

    @Test
    void rejectsScalarDocument() {
        var ex = catchThrowableOfType(InvalidSpecException.class, () -> parser.parse("just text"));

        assertThat(ex.messages()).containsExactly("Spec must be a YAML or JSON object");
    }

    @Test
    void rejectsMissingInfoAndPathsWithParserMessages() {
        var ex = catchThrowableOfType(InvalidSpecException.class, () -> parser.parse("openapi: 3.0.3\n"));

        assertThat(ex.messages()).isNotEmpty().hasSizeLessThanOrEqualTo(SpecParser.MAX_MESSAGES)
                .anySatisfy(m -> assertThat(m).contains("info"));
    }
}
