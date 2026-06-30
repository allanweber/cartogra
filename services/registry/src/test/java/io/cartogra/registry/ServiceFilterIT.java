package io.cartogra.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cartogra.registry.infrastructure.kafka.ServiceLifecycleEventProducer;
import io.cartogra.registry.infrastructure.kafka.TeamLifecycleEventProducer;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ServiceFilterIT {

    @MockitoBean
    ServiceLifecycleEventProducer eventProducer;

    @MockitoBean
    TeamLifecycleEventProducer teamEventProducer;

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
    void filterByTeamId() throws Exception {
        HttpResponse<String> teamResp = HTTP.send(
                post("/api/v1/teams", """
                        {"name":"filter-owner-team"}"""), HttpResponse.BodyHandlers.ofString());
        String teamId = MAPPER.readTree(teamResp.body()).get("data").get("id").asText();

        HttpResponse<String> owned = HTTP.send(
                post("/api/v1/services", String.format("""
                        {"name":"filter-owned-svc","teamId":"%s"}""", teamId)),
                HttpResponse.BodyHandlers.ofString());
        assertThat(owned.statusCode()).isEqualTo(201);
        String ownedId = MAPPER.readTree(owned.body()).get("data").get("id").asText();

        HTTP.send(post("/api/v1/services", """
                {"name":"filter-unowned-svc"}"""), HttpResponse.BodyHandlers.ofString());

        HttpResponse<String> filtered = HTTP.send(
                get("/api/v1/services?teamId=" + teamId + "&limit=50"), HttpResponse.BodyHandlers.ofString());
        assertThat(filtered.statusCode()).isEqualTo(200);
        JsonNode items = MAPPER.readTree(filtered.body()).get("data").get("items");
        boolean found = false;
        for (JsonNode item : items) {
            assertThat(item.get("teamId").asText()).isEqualTo(teamId);
            if (ownedId.equals(item.get("id").asText())) found = true;
        }
        assertThat(found).as("owned service should appear in teamId-filtered results").isTrue();
    }

    @Test
    void filterByHealthStatus() throws Exception {
        HttpResponse<String> created = HTTP.send(
                post("/api/v1/services", """
                        {"name":"filter-health-svc"}"""), HttpResponse.BodyHandlers.ofString());
        String svcId = MAPPER.readTree(created.body()).get("data").get("id").asText();

        HTTP.send(put("/api/v1/services/" + svcId, """
                        {"name":"filter-health-svc","healthStatus":"healthy"}"""),
                HttpResponse.BodyHandlers.ofString());

        HttpResponse<String> filtered = HTTP.send(
                get("/api/v1/services?health=healthy&limit=50"), HttpResponse.BodyHandlers.ofString());
        assertThat(filtered.statusCode()).isEqualTo(200);
        JsonNode items = MAPPER.readTree(filtered.body()).get("data").get("items");
        boolean found = false;
        for (JsonNode item : items) {
            if (svcId.equals(item.get("id").asText())) {
                found = true;
                break;
            }
        }
        assertThat(found).as("service updated to healthy should appear in health=healthy results").isTrue();
    }

    @Test
    void filterByDegradedIncludesUnknown() throws Exception {
        // New services start with UNKNOWN health — they must appear under health=degraded
        HttpResponse<String> created = HTTP.send(
                post("/api/v1/services", """
                        {"name":"filter-degraded-unknown-svc"}"""), HttpResponse.BodyHandlers.ofString());
        assertThat(created.statusCode()).isEqualTo(201);
        String svcId = MAPPER.readTree(created.body()).get("data").get("id").asText();

        HttpResponse<String> filtered = HTTP.send(
                get("/api/v1/services?health=degraded&limit=50"), HttpResponse.BodyHandlers.ofString());
        assertThat(filtered.statusCode()).isEqualTo(200);
        JsonNode items = MAPPER.readTree(filtered.body()).get("data").get("items");
        boolean found = false;
        for (JsonNode item : items) {
            if (svcId.equals(item.get("id").asText())) { found = true; break; }
        }
        assertThat(found).as("new service with UNKNOWN health must appear under health=degraded filter").isTrue();
    }

    @Test
    void filterByTechStack() throws Exception {
        HttpResponse<String> created = HTTP.send(
                post("/api/v1/services", """
                        {"name":"filter-techstack-svc","techStack":["golang"]}"""),
                HttpResponse.BodyHandlers.ofString());
        assertThat(created.statusCode()).isEqualTo(201);
        String svcId = MAPPER.readTree(created.body()).get("data").get("id").asText();

        HttpResponse<String> filtered = HTTP.send(
                get("/api/v1/services?techStack=golang&limit=50"), HttpResponse.BodyHandlers.ofString());
        assertThat(filtered.statusCode()).isEqualTo(200);
        JsonNode items = MAPPER.readTree(filtered.body()).get("data").get("items");
        boolean found = false;
        for (JsonNode item : items) {
            if (svcId.equals(item.get("id").asText())) {
                found = true;
                break;
            }
        }
        assertThat(found).as("service with golang tech stack should appear in techStack=golang results").isTrue();
    }

    @Test
    void techStacksEndpoint() throws Exception {
        HTTP.send(post("/api/v1/services", """
                {"name":"ts-endpoint-svc","techStack":["rust","wasm"]}"""),
                HttpResponse.BodyHandlers.ofString());

        HttpResponse<String> resp = HTTP.send(
                get("/api/v1/services/tech-stacks"), HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode items = MAPPER.readTree(resp.body()).get("data");
        assertThat(items.isArray()).isTrue();
        List<String> stacks = new ArrayList<>();
        items.forEach(n -> stacks.add(n.asText()));
        assertThat(stacks).contains("rust", "wasm");
    }

    @Test
    void filterBySearch() throws Exception {
        HttpResponse<String> created = HTTP.send(
                post("/api/v1/services", """
                        {"name":"billing-processor","description":"processes invoices for billing"}"""),
                HttpResponse.BodyHandlers.ofString());
        assertThat(created.statusCode()).isEqualTo(201);
        String svcId = MAPPER.readTree(created.body()).get("data").get("id").asText();

        HttpResponse<String> filtered = HTTP.send(
                get("/api/v1/services?search=billing&limit=50"), HttpResponse.BodyHandlers.ofString());
        assertThat(filtered.statusCode()).isEqualTo(200);
        JsonNode items = MAPPER.readTree(filtered.body()).get("data").get("items");
        boolean found = false;
        for (JsonNode item : items) {
            if (svcId.equals(item.get("id").asText())) {
                found = true;
                break;
            }
        }
        assertThat(found).as("service matching search term should appear in results").isTrue();
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
