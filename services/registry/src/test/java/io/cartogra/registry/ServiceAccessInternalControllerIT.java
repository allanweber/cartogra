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
 * Backs Topology's declared-dependency authorization check (Topology issue [1.2]): "is this
 * user a member of the team owning this service?" Tenant-scoped, unlike the cross-tenant
 * {@code /internal/services} (see {@link ServiceInternalControllerIT}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ServiceAccessInternalControllerIT {

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

    private UUID createTeam(UUID tenantId) {
        UUID teamId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO teams (id, tenant_id, name) VALUES (:id, :tenantId, :name)
                """,
                new MapSqlParameterSource()
                        .addValue("id", teamId)
                        .addValue("tenantId", tenantId)
                        .addValue("name", "team-" + teamId));
        return teamId;
    }

    private void addTeamMember(UUID tenantId, UUID teamId, UUID userId) {
        jdbcTemplate.update("""
                INSERT INTO team_members (tenant_id, team_id, user_id) VALUES (:tenantId, :teamId, :userId)
                """,
                new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("teamId", teamId)
                        .addValue("userId", userId));
    }

    private UUID createService(UUID tenantId, UUID teamId) throws Exception {
        String body = teamId == null
                ? """
                    {"name":"svc-%s"}
                    """.formatted(UUID.randomUUID())
                : """
                    {"name":"svc-%s","teamId":"%s"}
                    """.formatted(UUID.randomUUID(), teamId);
        HttpResponse<String> resp = HTTP.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/api/v1/registry/services"))
                        .header("Content-Type", "application/json")
                        .header("X-Tenant-Id", tenantId.toString())
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(201);
        return UUID.fromString(objectMapper.readTree(resp.body()).get("data").get("id").stringValue());
    }

    private JsonNode checkAccess(UUID tenantId, UUID userId, UUID... serviceIds) throws Exception {
        StringBuilder ids = new StringBuilder();
        for (int i = 0; i < serviceIds.length; i++) {
            if (i > 0) {
                ids.append(',');
            }
            ids.append('"').append(serviceIds[i]).append('"');
        }
        String body = """
                {"tenantId":"%s","userId":"%s","serviceIds":[%s]}
                """.formatted(tenantId, userId, ids);
        HttpResponse<String> resp = HTTP.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/api/v1/registry/internal/services/access"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);
        return objectMapper.readTree(resp.body()).get("data");
    }

    @Test
    void memberOfOwningTeam_returnsTrue() throws Exception {
        UUID tenantId = seedTenant();
        UUID teamId = createTeam(tenantId);
        UUID userId = UUID.randomUUID();
        addTeamMember(tenantId, teamId, userId);
        UUID serviceId = createService(tenantId, teamId);

        JsonNode data = checkAccess(tenantId, userId, serviceId);
        assertThat(data.get(serviceId.toString()).booleanValue()).isTrue();
    }

    @Test
    void nonMemberOfOwningTeam_returnsFalse() throws Exception {
        UUID tenantId = seedTenant();
        UUID teamId = createTeam(tenantId);
        UUID serviceId = createService(tenantId, teamId);
        UUID unrelatedUserId = UUID.randomUUID();

        JsonNode data = checkAccess(tenantId, unrelatedUserId, serviceId);
        assertThat(data.get(serviceId.toString()).booleanValue()).isFalse();
    }

    @Test
    void serviceWithNoTeam_returnsFalse() throws Exception {
        UUID tenantId = seedTenant();
        UUID serviceId = createService(tenantId, null);
        UUID userId = UUID.randomUUID();

        JsonNode data = checkAccess(tenantId, userId, serviceId);
        assertThat(data.get(serviceId.toString()).booleanValue()).isFalse();
    }

    @Test
    void unknownServiceId_returnsFalseNotError() throws Exception {
        UUID tenantId = seedTenant();
        UUID unknownServiceId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        JsonNode data = checkAccess(tenantId, userId, unknownServiceId);
        assertThat(data.get(unknownServiceId.toString()).booleanValue()).isFalse();
    }

    @Test
    void crossTenantServiceId_returnsFalse() throws Exception {
        UUID tenantA = seedTenant();
        UUID tenantB = seedTenant();
        UUID teamA = createTeam(tenantA);
        UUID userId = UUID.randomUUID();
        addTeamMember(tenantA, teamA, userId);
        UUID serviceInTenantA = createService(tenantA, teamA);

        JsonNode data = checkAccess(tenantB, userId, serviceInTenantA);
        assertThat(data.get(serviceInTenantA.toString()).booleanValue()).isFalse();
    }

    @Test
    void batchRequest_returnsOneBooleanPerServiceId() throws Exception {
        UUID tenantId = seedTenant();
        UUID teamId = createTeam(tenantId);
        UUID userId = UUID.randomUUID();
        addTeamMember(tenantId, teamId, userId);
        UUID ownedServiceId = createService(tenantId, teamId);
        UUID otherTeamServiceId = createService(tenantId, createTeam(tenantId));

        JsonNode data = checkAccess(tenantId, userId, ownedServiceId, otherTeamServiceId);
        assertThat(data.get(ownedServiceId.toString()).booleanValue()).isTrue();
        assertThat(data.get(otherTeamServiceId.toString()).booleanValue()).isFalse();
    }
}
