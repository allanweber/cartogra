package io.cartogra.registry;

import io.cartogra.test.OpenApiContractValidator;
import io.cartogra.test.PostgresTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(
        partitions = 1,
        bootstrapServersProperty = "spring.kafka.bootstrap-servers",
        topics = {
                "cartogra.registry.service.registered",
                "cartogra.registry.service.updated",
                "cartogra.registry.service.deleted"
        }
)
class RegistryEnvelopeContractTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final UUID TENANT = UUID.randomUUID();

    @Autowired
    private ObjectMapper objectMapper;

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> PostgresTestSupport.POSTGRES.getJdbcUrl() + "&currentSchema=registry");
        registry.add("spring.datasource.username", PostgresTestSupport.POSTGRES::getUsername);
        registry.add("spring.datasource.password", PostgresTestSupport.POSTGRES::getPassword);
    }

    private final OpenApiContractValidator contractValidator =
            new OpenApiContractValidator("registry.openapi.yaml");

    // ── Services ──────────────────────────────────────────────────────────

    @Test
    void servicesPost201ConformsToSpec() throws Exception {
        HttpResponse<String> response = HTTP.send(
                post("/api/v1/services", """
                        {"name":"contract-svc-%s"}""".formatted(UUID.randomUUID())),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(201);
        assertConformsToSpec("POST", "/api/v1/services", response);
    }

    @Test
    void servicesGet200ConformsToSpec() throws Exception {
        HTTP.send(post("/api/v1/services", """
                        {"name":"list-contract-svc-%s"}""".formatted(UUID.randomUUID())),
                HttpResponse.BodyHandlers.ofString());

        HttpResponse<String> response = HTTP.send(
                get("/api/v1/services"),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertConformsToSpec("GET", "/api/v1/services", response);
    }

    @Test
    void servicesGetById200ConformsToSpec() throws Exception {
        HttpResponse<String> created = HTTP.send(
                post("/api/v1/services", """
                        {"name":"get-by-id-svc-%s"}""".formatted(UUID.randomUUID())),
                HttpResponse.BodyHandlers.ofString());
        String id = objectMapper.readValue(created.body(), CreatedResponse.class).data().id();

        HttpResponse<String> response = HTTP.send(
                get("/api/v1/services/" + id),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertConformsToSpec("GET", "/api/v1/services/" + id, response);
    }

    @Test
    void servicesGetByIdNotFoundConformsToSpec() throws Exception {
        UUID missingId = UUID.randomUUID();
        HttpResponse<String> response = HTTP.send(
                get("/api/v1/services/" + missingId),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(404);
        assertConformsToSpec("GET", "/api/v1/services/" + missingId, response);
    }

    @Test
    void servicesPostInvalidBodyConformsToSpec() throws Exception {
        HttpResponse<String> response = HTTP.send(
                post("/api/v1/services", "{}"),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(400);
        assertConformsToSpec("POST", "/api/v1/services", response);
    }

    // ── Teams ─────────────────────────────────────────────────────────────

    @Test
    void teamsPost201ConformsToSpec() throws Exception {
        HttpResponse<String> response = HTTP.send(
                post("/api/v1/teams", """
                        {"name":"contract-team-%s"}""".formatted(UUID.randomUUID())),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(201);
        assertConformsToSpec("POST", "/api/v1/teams", response);
    }

    @Test
    void teamsGet200ConformsToSpec() throws Exception {
        HTTP.send(post("/api/v1/teams", """
                        {"name":"list-team-%s"}""".formatted(UUID.randomUUID())),
                HttpResponse.BodyHandlers.ofString());

        HttpResponse<String> response = HTTP.send(
                get("/api/v1/teams"),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertConformsToSpec("GET", "/api/v1/teams", response);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private void assertConformsToSpec(String method, String path,
                                      HttpResponse<String> response) throws Exception {
        Map<String, String> headers = response.headers().map().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().getFirst()));
        contractValidator.assertResponse(method, path, response.statusCode(),
                response.body(), headers);

        if (response.statusCode() == 204) {
            String traceIdHeader = response.headers().firstValue("x-trace-id").orElse("");
            assertThat(traceIdHeader).matches("[0-9a-f]{32}");
        } else {
            String traceId = objectMapper.readValue(response.body(), TraceResponse.class).traceId();
            assertThat(traceId).matches("[0-9a-f]{32}");
            assertThat(response.headers().firstValue("x-trace-id")).hasValue(traceId);
        }
    }

    private HttpRequest post(String path, String body) {
        return HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("X-Tenant-Id", TENANT.toString())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private HttpRequest get(String path) {
        return HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", TENANT.toString())
                .GET()
                .build();
    }

    // Minimal records for extracting fields from response JSON without using JsonNode text methods.
    private record EntityIdData(String id) {}
    private record CreatedResponse(EntityIdData data) {}
    private record TraceResponse(String traceId) {}
}
