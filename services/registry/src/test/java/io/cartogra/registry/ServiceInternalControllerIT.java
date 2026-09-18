package io.cartogra.registry;

import io.cartogra.test.KafkaTestSupport;
import io.cartogra.test.PostgresTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cross-tenant internal endpoint that backs Topology's admin backfill (see Topology issue
 * [1.1]: "walks the registry once for tenants that predate the consumer").
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ServiceInternalControllerIT {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private NamedParameterJdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> PostgresTestSupport.POSTGRES.getJdbcUrl() + "&currentSchema=registry");
        registry.add("spring.datasource.username", PostgresTestSupport.POSTGRES::getUsername);
        registry.add("spring.datasource.password", PostgresTestSupport.POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KafkaTestSupport.KAFKA::getBootstrapServers);
    }

    private void seedTenant(UUID tenantId) {
        jdbcTemplate.update("""
                INSERT INTO tenants (tenant_id, name, slug, plan_id)
                SELECT :tenantId, 'Test Tenant', 'test-tenant-' || :tenantId, (SELECT id FROM billing_plans WHERE slug = 'free')
                WHERE NOT EXISTS (SELECT 1 FROM tenants WHERE tenant_id = :tenantId)
                """,
                new MapSqlParameterSource().addValue("tenantId", tenantId));
    }

    private void createService(UUID tenantId, String name) throws Exception {
        seedTenant(tenantId);
        String body = """
                {"name":"%s"}
                """.formatted(name);
        HttpResponse<String> resp = HTTP.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/api/v1/registry/services"))
                        .header("Content-Type", "application/json")
                        .header("X-Tenant-Id", tenantId.toString())
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(201);
    }

    @Test
    void listsActiveServicesAcrossTenants() throws Exception {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        String nameA = "cross-tenant-svc-a-" + UUID.randomUUID();
        String nameB = "cross-tenant-svc-b-" + UUID.randomUUID();
        createService(tenantA, nameA);
        createService(tenantB, nameB);

        HttpResponse<String> resp = HTTP.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/api/v1/registry/internal/services?limit=1000&offset=0"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode items = objectMapper.readTree(resp.body()).get("data").get("items");
        assertThat(items.isArray()).isTrue();

        boolean foundA = false;
        boolean foundB = false;
        for (JsonNode item : items) {
            String name = item.get("name").stringValue();
            if (nameA.equals(name)) {
                foundA = true;
                assertThat(item.get("tenantId").stringValue()).isEqualTo(tenantA.toString());
            }
            if (nameB.equals(name)) {
                foundB = true;
                assertThat(item.get("tenantId").stringValue()).isEqualTo(tenantB.toString());
            }
        }
        assertThat(foundA).as("service from tenant A should appear in the cross-tenant listing").isTrue();
        assertThat(foundB).as("service from tenant B should appear in the cross-tenant listing").isTrue();
    }

    @Test
    void paginatesWithLimitAndOffset() throws Exception {
        HttpResponse<String> resp = HTTP.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/api/v1/registry/internal/services?limit=1&offset=0"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode data = objectMapper.readTree(resp.body()).get("data");
        assertThat(data.get("items").isArray()).isTrue();
        assertThat(data.get("items").size()).isLessThanOrEqualTo(1);
        assertThat(data.get("limit").intValue()).isEqualTo(1);
        assertThat(data.get("offset").intValue()).isEqualTo(0);
    }
}
