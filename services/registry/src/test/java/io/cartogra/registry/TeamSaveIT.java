package io.cartogra.registry;

import io.cartogra.web.security.InternalTokens;
import io.cartogra.common.identity.Actor;
import io.cartogra.registry.domain.TeamService;
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
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Saving a team (name, owned services, members) is one request with a fixed number of queries. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(StatementCountingConfig.class)
class TeamSaveIT {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Autowired
    private TeamService teamService;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> PostgresTestSupport.POSTGRES.getJdbcUrl() + "&currentSchema=registry");
        registry.add("spring.datasource.username", PostgresTestSupport.POSTGRES::getUsername);
        registry.add("spring.datasource.password", PostgresTestSupport.POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KafkaTestSupport.KAFKA::getBootstrapServers);
    }

    @Test
    void oneRequestSavesNameServicesAndMembers() throws Exception {
        UUID tenant = seedTenant();
        UUID team = createTeam(tenant);
        UUID kept = createService(tenant, team);
        UUID dropped = createService(tenant, team);
        UUID orphan1 = createService(tenant, null);
        UUID orphan2 = createService(tenant, null);
        UUID keptMember = UUID.randomUUID();
        UUID droppedMember = UUID.randomUUID();
        UUID newMember = createUser(tenant);
        addMember(tenant, team, keptMember);
        addMember(tenant, team, droppedMember);
        String historySql = "SELECT COUNT(*) FROM services_history WHERE tenant_id = :t AND service_id IN (:ids)";
        List<UUID> changed = List.of(dropped, orphan1, orphan2);
        long historyBefore = count(historySql, tenant, changed);

        HttpResponse<String> resp = HTTP.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/registry/teams/" + team))
                .header("Content-Type", "application/json")
                .header("X-Tenant-Id", tenant.toString())
                .header(InternalTokens.HEADER, InternalTokens.token("registry", tenant, UUID.randomUUID(), "ADMIN"))
                .PUT(HttpRequest.BodyPublishers.ofString("""
                        {"name":"renamed-%s","serviceIds":["%s","%s","%s"],"memberIds":["%s","%s"]}
                        """.formatted(team, kept, orphan1, orphan2, keptMember, newMember)))
                .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(resp.body()).get("data").get("name").stringValue())
                .isEqualTo("renamed-" + team);
        assertThat(ownedServices(tenant, team)).containsExactlyInAnyOrder(kept, orphan1, orphan2);
        assertThat(teamOf(tenant, dropped)).isNull();
        assertThat(members(tenant, team)).containsExactlyInAnyOrder(keptMember, newMember);
        assertThat(count(historySql, tenant, changed)).isEqualTo(historyBefore + 3);
        assertThat(count("""
                SELECT COUNT(*) FROM audit_events
                WHERE tenant_id = :t AND entity_id IN (:ids) AND action = 'owner_changed'
                """, tenant, changed)).isEqualTo(3);
        assertThat(count("""
                SELECT COUNT(*) FROM audit_events
                WHERE tenant_id = :t AND entity_id IN (:ids) AND action IN ('member_added', 'member_removed')
                """, tenant, List.of(team))).isEqualTo(2);
    }

    @Test
    void savingWithoutTheListsIsRejectedAndChangesNothing() throws Exception {
        UUID tenant = seedTenant();
        UUID team = createTeam(tenant);
        UUID owned = createService(tenant, team);
        UUID member = UUID.randomUUID();
        addMember(tenant, team, member);

        for (String body : List.of("{\"name\":\"n\"}", "{\"name\":\"n\",\"serviceIds\":[]}",
                "{\"name\":\"n\",\"memberIds\":[]}")) {
            assertThat(putTeam(tenant, team, body).statusCode()).isEqualTo(400);
        }
        assertThat(ownedServices(tenant, team)).containsExactly(owned);
        assertThat(members(tenant, team)).containsExactly(member);
    }

    @Test
    void emptyListsClearServicesAndMembers() throws Exception {
        UUID tenant = seedTenant();
        UUID team = createTeam(tenant);
        UUID owned = createService(tenant, team);
        addMember(tenant, team, UUID.randomUUID());

        HttpResponse<String> resp = putTeam(tenant, team,
                "{\"name\":\"team-%s\",\"serviceIds\":[],\"memberIds\":[]}".formatted(team));

        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(ownedServices(tenant, team)).isEmpty();
        assertThat(teamOf(tenant, owned)).isNull();
        assertThat(members(tenant, team)).isEmpty();
    }

    @Test
    void unknownServiceIdReturns404AndChangesNothing() throws Exception {
        UUID tenant = seedTenant();
        UUID team = createTeam(tenant);
        UUID owned = createService(tenant, team);

        HttpResponse<String> resp = putTeam(tenant, team,
                "{\"name\":\"team-%s\",\"serviceIds\":[\"%s\"],\"memberIds\":[]}".formatted(team, UUID.randomUUID()));

        assertThat(resp.statusCode()).isEqualTo(404);
        assertThat(ownedServices(tenant, team)).containsExactly(owned);
    }

    @Test
    void anotherTenantsServiceCannotBeTaken() throws Exception {
        UUID tenant = seedTenant();
        UUID otherTenant = seedTenant();
        UUID team = createTeam(tenant);
        UUID foreign = createService(otherTenant, null);

        HttpResponse<String> resp = putTeam(tenant, team,
                "{\"name\":\"team-%s\",\"serviceIds\":[\"%s\"],\"memberIds\":[]}".formatted(team, foreign));

        assertThat(resp.statusCode()).isEqualTo(404);
        assertThat(teamOf(otherTenant, foreign)).isNull();
    }

    @Test
    void anotherTenantsUserCannotBeAddedAndNothingIsSaved() throws Exception {
        UUID tenant = seedTenant();
        UUID otherTenant = seedTenant();
        UUID team = createTeam(tenant);
        UUID kept = createUser(tenant);
        addMember(tenant, team, kept);
        UUID foreignUser = createUser(otherTenant);

        HttpResponse<String> resp = putTeam(tenant, team,
                "{\"name\":\"team-%s\",\"serviceIds\":[],\"memberIds\":[\"%s\"]}".formatted(team, foreignUser));

        assertThat(resp.statusCode()).isEqualTo(404);
        assertThat(members(tenant, team)).containsExactly(kept);
    }

    @Test
    void deletedUserOfTheTenantCannotBeAdded() throws Exception {
        UUID tenant = seedTenant();
        UUID team = createTeam(tenant);
        UUID deletedUser = createUser(tenant);
        jdbc.update("UPDATE users SET deleted_at = now() WHERE id = :id", new MapSqlParameterSource("id", deletedUser));

        HttpResponse<String> resp = putTeam(tenant, team,
                "{\"name\":\"team-%s\",\"serviceIds\":[],\"memberIds\":[\"%s\"]}".formatted(team, deletedUser));

        assertThat(resp.statusCode()).isEqualTo(404);
        assertThat(members(tenant, team)).isEmpty();
    }

    @Test
    void addMemberEndpointRejectsAnotherTenantsUser() throws Exception {
        UUID tenant = seedTenant();
        UUID team = createTeam(tenant);
        UUID foreignUser = createUser(seedTenant());

        HttpResponse<String> resp = HTTP.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/registry/teams/" + team + "/members"))
                .header("Content-Type", "application/json")
                .header("X-Tenant-Id", tenant.toString())
                .header(InternalTokens.HEADER, InternalTokens.token("registry", tenant, null, "ADMIN"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"userId\":\"%s\"}".formatted(foreignUser)))
                .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(resp.statusCode()).isEqualTo(404);
        assertThat(members(tenant, team)).isEmpty();
    }

    @Test
    void nonAdminCannotTakeAnOrphanAndNothingIsSaved() throws Exception {
        UUID tenant = seedTenant();
        UUID team = createTeam(tenant);
        UUID userId = UUID.randomUUID();
        addMember(tenant, team, userId);
        UUID orphan = createService(tenant, null);

        HttpResponse<String> resp = HTTP.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/registry/teams/" + team))
                .header("Content-Type", "application/json")
                .header("X-Tenant-Id", tenant.toString())
                .header(InternalTokens.HEADER, InternalTokens.token("registry", tenant, userId, "MEMBER"))
                .PUT(HttpRequest.BodyPublishers.ofString(
                        "{\"name\":\"renamed-%s\",\"serviceIds\":[\"%s\"],\"memberIds\":[\"%s\"]}"
                                .formatted(team, orphan, userId)))
                .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(resp.statusCode()).isEqualTo(403);
        assertThat(teamOf(tenant, orphan)).isNull();
        assertThat(jdbc.queryForObject("SELECT name FROM teams WHERE id = :id",
                new MapSqlParameterSource("id", team), String.class)).isEqualTo("team-" + team);
    }

    private HttpResponse<String> putTeam(UUID tenantId, UUID teamId, String body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/registry/teams/" + teamId))
                .header("Content-Type", "application/json")
                .header("X-Tenant-Id", tenantId.toString())
                .header(InternalTokens.HEADER, InternalTokens.token("registry", tenantId, null, "ADMIN"))
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void queryCountDoesNotGrowWithTheNumberOfChanges() throws Exception {
        UUID tenant = seedTenant();
        UUID actorId = UUID.randomUUID();
        Actor actor = Actor.user(actorId);

        UUID smallTeam = createTeam(tenant);
        UUID smallSource = createTeam(tenant);
        addMember(tenant, smallTeam, actorId);
        addMember(tenant, smallSource, actorId);
        UUID smallDropped = createService(tenant, smallTeam);
        UUID smallTaken = createService(tenant, smallSource);
        UUID smallOldMember = UUID.randomUUID();
        addMember(tenant, smallTeam, smallOldMember);

        UUID bigTeam = createTeam(tenant);
        UUID bigSource = createTeam(tenant);
        addMember(tenant, bigTeam, actorId);
        addMember(tenant, bigSource, actorId);
        List<UUID> bigTaken = new ArrayList<>();
        List<UUID> bigOldMembers = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            createService(tenant, bigTeam);
            bigTaken.add(createService(tenant, bigSource));
            UUID oldMember = UUID.randomUUID();
            addMember(tenant, bigTeam, oldMember);
            bigOldMembers.add(oldMember);
        }
        Set<UUID> bigNewMembers = Stream.concat(Stream.of(actorId),
                Stream.generate(() -> createUser(tenant)).limit(5)).collect(Collectors.toSet());

        UUID smallNewMember = createUser(tenant);
        int small = SqlStatementCounter.count(() -> teamService.update(tenant, actor, smallTeam, "small-" + smallTeam,
                Set.of(smallTaken), Set.of(actorId, smallNewMember)));
        int big = SqlStatementCounter.count(() -> teamService.update(tenant, actor, bigTeam, "big-" + bigTeam,
                Set.copyOf(bigTaken), bigNewMembers));

        assertThat(big).isEqualTo(small);
        assertThat(teamOf(tenant, smallDropped)).isNull();
        assertThat(ownedServices(tenant, smallTeam)).containsExactly(smallTaken);
        assertThat(ownedServices(tenant, bigTeam)).containsExactlyInAnyOrderElementsOf(bigTaken);
        assertThat(members(tenant, bigTeam)).containsExactlyInAnyOrderElementsOf(bigNewMembers)
                .doesNotContainAnyElementsOf(bigOldMembers);
        assertThat(members(tenant, smallTeam)).doesNotContain(smallOldMember);
    }

    private UUID seedTenant() {
        UUID tenantId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO tenants (tenant_id, name, slug, plan_id)
                SELECT :tenantId, 'Test Tenant', 'test-tenant-' || :tenantId,
                       (SELECT id FROM billing_plans WHERE slug = 'enterprise')
                """, new MapSqlParameterSource().addValue("tenantId", tenantId));
        return tenantId;
    }

    private UUID createTeam(UUID tenantId) {
        UUID teamId = UUID.randomUUID();
        jdbc.update("INSERT INTO teams (id, tenant_id, name) VALUES (:id, :tenantId, :name)",
                new MapSqlParameterSource()
                        .addValue("id", teamId)
                        .addValue("tenantId", tenantId)
                        .addValue("name", "team-" + teamId));
        return teamId;
    }

    private UUID createUser(UUID tenantId) {
        return jdbc.queryForObject("""
                INSERT INTO users (tenant_id, email, auth_provider, roles)
                VALUES (:tenantId, :email, 'local', '{MEMBER}') RETURNING id
                """, new MapSqlParameterSource("tenantId", tenantId)
                .addValue("email", UUID.randomUUID() + "@test.invalid"), UUID.class);
    }

    private void addMember(UUID tenantId, UUID teamId, UUID userId) {
        jdbc.update("INSERT INTO team_members (tenant_id, team_id, user_id) VALUES (:tenantId, :teamId, :userId)",
                new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("teamId", teamId)
                        .addValue("userId", userId));
    }

    private UUID createService(UUID tenantId, UUID teamId) throws Exception {
        String body = teamId == null
                ? "{\"name\":\"svc-%s\"}".formatted(UUID.randomUUID())
                : "{\"name\":\"svc-%s\",\"teamId\":\"%s\"}".formatted(UUID.randomUUID(), teamId);
        HttpResponse<String> resp = HTTP.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/registry/services"))
                .header("Content-Type", "application/json")
                .header("X-Tenant-Id", tenantId.toString())
                .header(InternalTokens.HEADER, InternalTokens.token("registry", tenantId, null, "ADMIN"))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(201);
        return UUID.fromString(objectMapper.readTree(resp.body()).get("data").get("id").stringValue());
    }

    private List<UUID> ownedServices(UUID tenantId, UUID teamId) {
        return jdbc.queryForList("""
                SELECT id FROM services WHERE tenant_id = :t AND team_id = :team AND deleted_at IS NULL
                """, new MapSqlParameterSource().addValue("t", tenantId).addValue("team", teamId), UUID.class);
    }

    private UUID teamOf(UUID tenantId, UUID serviceId) {
        return jdbc.queryForObject("SELECT team_id FROM services WHERE tenant_id = :t AND id = :id",
                new MapSqlParameterSource().addValue("t", tenantId).addValue("id", serviceId), UUID.class);
    }

    private List<UUID> members(UUID tenantId, UUID teamId) {
        return jdbc.queryForList("SELECT user_id FROM team_members WHERE tenant_id = :t AND team_id = :team",
                new MapSqlParameterSource().addValue("t", tenantId).addValue("team", teamId), UUID.class);
    }

    private long count(String sql, UUID tenantId, List<UUID> ids) {
        Long n = jdbc.queryForObject(sql, new MapSqlParameterSource().addValue("t", tenantId).addValue("ids", ids),
                Long.class);
        return n == null ? 0 : n;
    }
}
