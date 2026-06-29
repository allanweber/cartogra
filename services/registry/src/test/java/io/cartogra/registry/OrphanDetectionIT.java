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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrphanDetectionIT {

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
    void serviceWithNoTeamAppearsInOrphaned() throws Exception {
        // create service without a team (orphan by default)
        HttpResponse<String> created = HTTP.send(
                post("/api/v1/services", """
                        {"name":"orphan-svc-no-team"}"""), HttpResponse.BodyHandlers.ofString());
        assertThat(created.statusCode()).isEqualTo(201);
        String serviceId = MAPPER.readTree(created.body()).get("data").get("id").asText();

        // must appear in /orphaned
        HttpResponse<String> orphaned = HTTP.send(
                get("/api/v1/services/orphaned?limit=100"), HttpResponse.BodyHandlers.ofString());
        assertThat(orphaned.statusCode()).isEqualTo(200);
        JsonNode items = MAPPER.readTree(orphaned.body()).get("data").get("items");
        boolean found = false;
        for (JsonNode item : items) {
            if (serviceId.equals(item.get("id").asText())) {
                found = true;
                break;
            }
        }
        assertThat(found).as("service without team should appear in /orphaned").isTrue();
    }

    @Test
    void serviceAssignedToTeamDoesNotAppearInOrphaned() throws Exception {
        // create a team
        HttpResponse<String> teamResp = HTTP.send(
                post("/api/v1/teams", """
                        {"name":"orphan-test-team"}"""), HttpResponse.BodyHandlers.ofString());
        assertThat(teamResp.statusCode()).isEqualTo(201);
        String teamId = MAPPER.readTree(teamResp.body()).get("data").get("id").asText();

        // create a service, then assign it to the team
        HttpResponse<String> svcResp = HTTP.send(
                post("/api/v1/services", """
                        {"name":"owned-svc"}"""), HttpResponse.BodyHandlers.ofString());
        assertThat(svcResp.statusCode()).isEqualTo(201);
        String serviceId = MAPPER.readTree(svcResp.body()).get("data").get("id").asText();

        HTTP.send(patch("/api/v1/services/" + serviceId + "/owner",
                "{\"teamId\":\"" + teamId + "\"}"), HttpResponse.BodyHandlers.ofString());

        // must NOT appear in /orphaned
        HttpResponse<String> orphaned = HTTP.send(
                get("/api/v1/services/orphaned?limit=100"), HttpResponse.BodyHandlers.ofString());
        assertThat(orphaned.statusCode()).isEqualTo(200);
        JsonNode items = MAPPER.readTree(orphaned.body()).get("data").get("items");
        for (JsonNode item : items) {
            assertThat(item.get("id").asText()).isNotEqualTo(serviceId);
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

    private HttpRequest patch(String path, String body) {
        return HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("X-Tenant-Id", TENANT.toString())
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
                .build();
    }
}
