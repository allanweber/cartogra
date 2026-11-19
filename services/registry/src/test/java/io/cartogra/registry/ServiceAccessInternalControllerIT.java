package io.cartogra.registry;

import io.cartogra.web.security.InternalTokens;
import io.cartogra.registry.domain.ServiceService;
import io.cartogra.test.KafkaTestSupport;
import io.cartogra.test.PostgresTestSupport;
import io.cartogra.test.SqlStatementCounter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Backs Topology's declared-dependency authorization check (Topology issue [1.2]): "is this
 * user a member of the team owning this service?" Tenant-scoped.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(StatementCountingConfig.class)
class ServiceAccessInternalControllerIT {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private NamedParameterJdbcTemplate jdbcTemplate;

    @Autowired
    private ServiceService serviceService;

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
                        .header(InternalTokens.HEADER, InternalTokens.token("registry", tenantId, null, "ADMIN"))
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(201);
        return UUID.fromString(objectMapper.readTree(resp.body()).get("data").get("id").stringValue());
    }

    private JsonNode checkAccess(UUID tenantId, UUID userId, UUID... serviceIds) throws Exception {
        HttpResponse<String> resp = postAccess(tenantId, userId, serviceIds);
        assertThat(resp.statusCode()).isEqualTo(200);
        return objectMapper.readTree(resp.body()).get("data");
    }

    private HttpResponse<String> postAccess(UUID tenantId, UUID userId, UUID... serviceIds) throws Exception {
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
        return HTTP.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/api/v1/registry/internal/services/access"))
                        .header("Content-Type", "application/json")
                        .header("X-Tenant-Id", tenantId.toString())
                        .header(InternalTokens.HEADER, InternalTokens.service("registry", tenantId))
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void manyServiceIds_resolvedWithOneQuery() throws Exception {
        UUID tenantId = seedTenant();
        UUID teamId = createTeam(tenantId);
        UUID userId = UUID.randomUUID();
        addTeamMember(tenantId, teamId, userId);
        UUID owned1 = createService(tenantId, teamId);
        UUID owned2 = createService(tenantId, teamId);
        UUID otherTeam = createService(tenantId, createTeam(tenantId));
        UUID noTeam = createService(tenantId, null);
        UUID unknown = UUID.randomUUID();
        List<UUID> ids = List.of(owned1, otherTeam, owned2, noTeam, unknown);

        AtomicReference<Map<UUID, Boolean>> result = new AtomicReference<>();
        int statements = SqlStatementCounter.count(() -> result.set(serviceService.checkAccess(tenantId, userId, ids)));

        assertThat(statements).isEqualTo(1);
        assertThat(result.get()).containsExactly(
                Map.entry(owned1, true), Map.entry(otherTeam, false), Map.entry(owned2, true),
                Map.entry(noTeam, false), Map.entry(unknown, false));
    }

    @Test
    void moreThanHundredServiceIds_rejected() throws Exception {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            ids.add(UUID.randomUUID());
        }
        HttpResponse<String> resp = postAccess(UUID.randomUUID(), UUID.randomUUID(), ids.toArray(UUID[]::new));
        assertThat(resp.statusCode()).isEqualTo(400);
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
