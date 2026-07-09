package io.cartogra.registry;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.cartogra.registry.infrastructure.kafka.ServiceLifecycleEventProducer;
import io.cartogra.registry.infrastructure.kafka.TeamLifecycleEventProducer;
import io.cartogra.test.PostgresTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
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

    private static final ObjectMapper MAPPER = new ObjectMapper();
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

    @Autowired
    private NamedParameterJdbcTemplate jdbcTemplate;

    @BeforeEach
    void seedTenant() {
        jdbcTemplate.update("""
            INSERT INTO tenants (tenant_id, name, slug, plan_id)
            SELECT :tenantId, 'Test Tenant', 'test-tenant-' || :tenantId, (SELECT id FROM billing_plans WHERE slug = 'free')
            WHERE NOT EXISTS (SELECT 1 FROM tenants WHERE tenant_id = :tenantId)
            """,
            new MapSqlParameterSource().addValue("tenantId", TENANT));
    }

    @Test
    void serviceWithNoTeamAppearsInOrphaned() throws Exception {
        // create service without a team (orphan by default)
        HttpResponse<String> created = HTTP.send(
                post("/api/v1/registry/services", """
                        {"name":"orphan-svc-no-team"}"""), HttpResponse.BodyHandlers.ofString());
        assertThat(created.statusCode()).isEqualTo(201);
        String serviceId = MAPPER.readTree(created.body()).get("data").get("id").asString();

        // must appear in /orphaned
        HttpResponse<String> orphaned = HTTP.send(
                get("/api/v1/registry/services/orphaned?limit=100"), HttpResponse.BodyHandlers.ofString());
        assertThat(orphaned.statusCode()).isEqualTo(200);
        JsonNode items = MAPPER.readTree(orphaned.body()).get("data").get("items");
        boolean found = false;
        for (JsonNode item : items) {
            if (serviceId.equals(item.get("id").asString())) {
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
                post("/api/v1/registry/teams", """
                        {"name":"orphan-test-team"}"""), HttpResponse.BodyHandlers.ofString());
        assertThat(teamResp.statusCode()).isEqualTo(201);
        String teamId = MAPPER.readTree(teamResp.body()).get("data").get("id").asString();

        // create a service, then assign it to the team
        HttpResponse<String> svcResp = HTTP.send(
                post("/api/v1/registry/services", """
                        {"name":"owned-svc"}"""), HttpResponse.BodyHandlers.ofString());
        assertThat(svcResp.statusCode()).isEqualTo(201);
        String serviceId = MAPPER.readTree(svcResp.body()).get("data").get("id").asString();

        HTTP.send(patch("/api/v1/registry/services/" + serviceId + "/owner",
                "{\"teamId\":\"" + teamId + "\"}"), HttpResponse.BodyHandlers.ofString());

        // must NOT appear in /orphaned
        HttpResponse<String> orphaned = HTTP.send(
                get("/api/v1/registry/services/orphaned?limit=100"), HttpResponse.BodyHandlers.ofString());
        assertThat(orphaned.statusCode()).isEqualTo(200);
        JsonNode items = MAPPER.readTree(orphaned.body()).get("data").get("items");
        for (JsonNode item : items) {
            assertThat(item.get("id").asString()).isNotEqualTo(serviceId);
        }
    }

    private HttpRequest post(String path, String body) {
        return HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("X-Tenant-Id", TENANT.toString())
                .header("X-User-Roles", "ADMIN")
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
                .header("X-User-Roles", "ADMIN")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
                .build();
    }
}
