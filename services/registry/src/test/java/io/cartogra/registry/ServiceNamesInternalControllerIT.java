package io.cartogra.registry;

import io.cartogra.web.security.InternalTokens;
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
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ServiceNamesInternalControllerIT {

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
        registry.add("spring.datasource.username", () -> PostgresTestSupport.APP_USER);
        registry.add("spring.datasource.password", () -> PostgresTestSupport.APP_PASSWORD);
        registry.add("spring.flyway.user", () -> PostgresTestSupport.OWNER_USER);
        registry.add("spring.flyway.password", () -> PostgresTestSupport.OWNER_PASSWORD);
        registry.add("spring.kafka.bootstrap-servers", KafkaTestSupport.KAFKA::getBootstrapServers);
    }

    @Test
    void returnsNamesOfKnownServicesOnly() throws Exception {
        UUID tenantId = seedTenant();
        UUID other = seedTenant();
        String name = "svc-" + UUID.randomUUID();
        UUID serviceId = createService(tenantId, name);
        UUID otherTenantService = createService(other, "svc-" + UUID.randomUUID());
        UUID deleted = createService(tenantId, "svc-" + UUID.randomUUID());
        send("DELETE", "/api/v1/registry/services/" + deleted, tenantId, null);
        UUID unknown = UUID.randomUUID();

        HttpResponse<String> resp = names(tenantId, serviceId, otherTenantService, deleted, unknown);

        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode data = objectMapper.readTree(resp.body()).get("data");
        assertThat(data.size()).isEqualTo(1);
        assertThat(data.get(serviceId.toString()).stringValue()).isEqualTo(name);
    }

    @Test
    void rejectsAnEmptyRequest() throws Exception {
        assertThat(names(seedTenant()).statusCode()).isEqualTo(400);
    }

    private HttpResponse<String> names(UUID tenantId, UUID... serviceIds) throws Exception {
        String ids = Arrays.stream(serviceIds).map(id -> "\"" + id + "\"").collect(Collectors.joining(","));
        return send("POST", "/api/v1/registry/internal/services/names", tenantId, """
                {"tenantId":"%s","serviceIds":[%s]}
                """.formatted(tenantId, ids));
    }

    private UUID createService(UUID tenantId, String name) throws Exception {
        HttpResponse<String> resp = send("POST", "/api/v1/registry/services", tenantId, """
                {"name":"%s"}
                """.formatted(name));
        assertThat(resp.statusCode()).isEqualTo(201);
        return UUID.fromString(objectMapper.readTree(resp.body()).get("data").get("id").stringValue());
    }

    private HttpResponse<String> send(String method, String path, UUID tenantId, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (tenantId != null) {
            String token = path.startsWith("/api/v1/registry/internal/")
                    ? InternalTokens.service("registry", tenantId)
                    : InternalTokens.user("registry", tenantId, "ADMIN");
            builder.header("X-Tenant-Id", tenantId.toString()).header(InternalTokens.HEADER, token);
        }
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private UUID seedTenant() {
        UUID tenantId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO tenants (tenant_id, name, slug, plan_id)
                SELECT :tenantId, 'Test Tenant', 'test-tenant-' || :tenantId, (SELECT id FROM billing_plans WHERE slug = 'free')
                WHERE NOT EXISTS (SELECT 1 FROM tenants WHERE tenant_id = :tenantId)
                """,
                new MapSqlParameterSource().addValue("tenantId", tenantId));
        return tenantId;
    }
}
