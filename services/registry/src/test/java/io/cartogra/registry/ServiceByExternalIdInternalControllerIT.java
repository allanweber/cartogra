package io.cartogra.registry;

import io.cartogra.test.KafkaTestSupport;
import io.cartogra.test.PostgresTestSupport;
import io.cartogra.web.security.InternalTokens;
import io.cartogra.web.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ServiceByExternalIdInternalControllerIT {

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
    void returnsTheServiceIdOfALiveServiceWithTheExternalId() throws Exception {
        UUID tenantId = seedTenant();
        String externalId = "acme/payments-" + UUID.randomUUID();
        UUID serviceId = insertScmService(tenantId, externalId);

        HttpResponse<String> resp = find(tenantId, externalId, true);

        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(resp.body()).get("data").get("serviceId").stringValue())
                .isEqualTo(serviceId.toString());
    }

    @Test
    void unknownDeletedOrCrossTenantExternalIdGives404() throws Exception {
        UUID tenantId = seedTenant();
        UUID other = seedTenant();
        String otherTenants = "acme/other-" + UUID.randomUUID();
        insertScmService(other, otherTenants);
        String deleted = "acme/deleted-" + UUID.randomUUID();
        UUID deletedId = insertScmService(tenantId, deleted);
        TenantContext.runAs(tenantId, () -> jdbcTemplate.update(
                "UPDATE services SET deleted_at = now() WHERE id = :id", new MapSqlParameterSource("id", deletedId)));

        assertThat(find(tenantId, "acme/unknown-" + UUID.randomUUID(), true).statusCode()).isEqualTo(404);
        assertThat(find(tenantId, otherTenants, true).statusCode()).isEqualTo(404);
        assertThat(find(tenantId, deleted, true).statusCode()).isEqualTo(404);
    }

    @Test
    void callWithoutAnInternalTokenGives401() throws Exception {
        UUID tenantId = seedTenant();
        assertThat(find(tenantId, "acme/any", false).statusCode()).isEqualTo(401);
    }

    private HttpResponse<String> find(UUID tenantId, String externalId, boolean withToken) throws Exception {
        String path = "/api/v1/registry/internal/services/by-external-id?tenantId=" + tenantId
                + "&externalId=" + URLEncoder.encode(externalId, StandardCharsets.UTF_8);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", tenantId.toString())
                .GET();
        if (withToken) {
            builder.header(InternalTokens.HEADER, InternalTokens.service("registry", tenantId));
        }
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private UUID insertScmService(UUID tenantId, String externalId) {
        return TenantContext.callAs(tenantId, () -> jdbcTemplate.queryForObject("""
                INSERT INTO services (tenant_id, name, external_id, source)
                VALUES (:tenantId, :name, :externalId, 'github')
                RETURNING id
                """,
                new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("name", "svc-" + UUID.randomUUID())
                        .addValue("externalId", externalId),
                UUID.class));
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
