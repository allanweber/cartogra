package io.cartogra.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cartogra.registry.infrastructure.kafka.ServiceLifecycleEventProducer;
import io.cartogra.test.PostgresTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ServiceHistoryIT {

    @MockitoBean
    ServiceLifecycleEventProducer eventProducer;

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final UUID TENANT = UUID.randomUUID();

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> PostgresTestSupport.POSTGRES.getJdbcUrl() + "&currentSchema=registry");
        registry.add("spring.datasource.username", PostgresTestSupport.POSTGRES::getUsername);
        registry.add("spring.datasource.password", PostgresTestSupport.POSTGRES::getPassword);
    }

    @LocalServerPort
    private int port;

    @Test
    void updateProducesHistoryRow() throws Exception {
        // create
        HttpResponse<String> created = HTTP.send(
                post("/api/v1/services", """
                        {"name":"history-test-svc"}"""), HttpResponse.BodyHandlers.ofString());
        assertThat(created.statusCode()).isEqualTo(201);
        String serviceId = MAPPER.readTree(created.body()).get("data").get("id").asText();

        // update (generates second history row)
        HTTP.send(put("/api/v1/services/" + serviceId, """
                {"name":"history-test-svc","description":"updated","healthStatus":"healthy"}"""),
                HttpResponse.BodyHandlers.ofString());

        // history should have at least 2 entries
        HttpResponse<String> history = HTTP.send(
                get("/api/v1/services/" + serviceId + "/history"),
                HttpResponse.BodyHandlers.ofString());
        assertThat(history.statusCode()).isEqualTo(200);
        JsonNode data = MAPPER.readTree(history.body()).get("data");
        assertThat(data.get("total").asLong()).isGreaterThanOrEqualTo(2);
        assertThat(data.get("items").get(0).has("snapshot")).isTrue();
    }

    @Test
    void pointInTimeQueryReturnsSnapshot() throws Exception {
        HttpResponse<String> created = HTTP.send(
                post("/api/v1/services", """
                        {"name":"pit-test-svc"}"""), HttpResponse.BodyHandlers.ofString());
        assertThat(created.statusCode()).isEqualTo(201);
        String serviceId = MAPPER.readTree(created.body()).get("data").get("id").asText();

        // query at "now" — should return the creation snapshot
        String atNow = java.time.Instant.now().toString();
        HttpResponse<String> pit = HTTP.send(
                get("/api/v1/services/" + serviceId + "/history?at=" + java.net.URLEncoder.encode(atNow, "UTF-8")),
                HttpResponse.BodyHandlers.ofString());
        assertThat(pit.statusCode()).isEqualTo(200);
        JsonNode items = MAPPER.readTree(pit.body()).get("data").get("items");
        assertThat(items).isNotNull();
        assertThat(items.size()).isEqualTo(1);
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

    private HttpRequest put(String path, String body) {
        return HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("X-Tenant-Id", TENANT.toString())
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }
}
