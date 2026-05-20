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
class ServiceCrudIT {

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
    void createReadUpdateDelete() throws Exception {
        // CREATE
        String body = """
                {"name":"payments-svc","description":"handles payments"}
                """;
        HttpResponse<String> created = HTTP.send(
                post("/api/v1/services", body), HttpResponse.BodyHandlers.ofString());
        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode data = MAPPER.readTree(created.body()).get("data");
        String serviceId = data.get("id").asText();
        assertThat(data.get("name").asText()).isEqualTo("payments-svc");
        assertThat(created.headers().firstValue("X-Trace-Id")).isPresent();

        // READ
        HttpResponse<String> got = HTTP.send(
                get("/api/v1/services/" + serviceId), HttpResponse.BodyHandlers.ofString());
        assertThat(got.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(got.body()).get("data").get("name").asText()).isEqualTo("payments-svc");

        // UPDATE
        String updateBody = """
                {"name":"payments-service","description":"handles payments v2","healthStatus":"healthy"}
                """;
        HttpResponse<String> updated = HTTP.send(
                put("/api/v1/services/" + serviceId, updateBody), HttpResponse.BodyHandlers.ofString());
        assertThat(updated.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(updated.body()).get("data").get("name").asText()).isEqualTo("payments-service");

        // DELETE
        HttpResponse<String> deleted = HTTP.send(
                delete("/api/v1/services/" + serviceId), HttpResponse.BodyHandlers.ofString());
        assertThat(deleted.statusCode()).isEqualTo(204);

        // 404 after delete
        HttpResponse<String> gone = HTTP.send(
                get("/api/v1/services/" + serviceId), HttpResponse.BodyHandlers.ofString());
        assertThat(gone.statusCode()).isEqualTo(404);
    }

    @Test
    void listServices() throws Exception {
        HTTP.send(post("/api/v1/services", """
                {"name":"list-test-svc-1"}"""), HttpResponse.BodyHandlers.ofString());
        HTTP.send(post("/api/v1/services", """
                {"name":"list-test-svc-2"}"""), HttpResponse.BodyHandlers.ofString());

        HttpResponse<String> list = HTTP.send(
                get("/api/v1/services?limit=50&offset=0"), HttpResponse.BodyHandlers.ofString());
        assertThat(list.statusCode()).isEqualTo(200);
        JsonNode result = MAPPER.readTree(list.body()).get("data");
        assertThat(result.get("total").asLong()).isGreaterThanOrEqualTo(2);
        assertThat(result.get("items").isArray()).isTrue();
    }

    @Test
    void missingServiceReturns404() throws Exception {
        HttpResponse<String> resp = HTTP.send(
                get("/api/v1/services/" + UUID.randomUUID()), HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(404);
        JsonNode error = MAPPER.readTree(resp.body()).get("error");
        assertThat(error.get("code").asText()).isEqualTo("NOT_FOUND");
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

    private HttpRequest delete(String path) {
        return HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", TENANT.toString())
                .DELETE()
                .build();
    }
}
