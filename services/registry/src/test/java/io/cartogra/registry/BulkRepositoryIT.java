package io.cartogra.registry;

import io.cartogra.common.audit.AuditEvent;
import io.cartogra.common.audit.AuditEventPort;
import io.cartogra.common.audit.AuditKind;
import io.cartogra.common.identity.Actor;
import io.cartogra.registry.domain.Service;
import io.cartogra.registry.domain.ServiceHealthStatus;
import io.cartogra.registry.domain.ServiceSnapshot;
import io.cartogra.registry.domain.TeamMember;
import io.cartogra.registry.repository.ServiceHistoryRepository;
import io.cartogra.registry.repository.ServiceRepository;
import io.cartogra.registry.repository.TeamRepository;
import io.cartogra.test.KafkaTestSupport;
import io.cartogra.test.PostgresTestSupport;
import io.cartogra.test.SqlStatementCounter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** The set-based repository writes and reads behind team save and the access check: one statement each. */
@SpringBootTest
@Import(StatementCountingConfig.class)
class BulkRepositoryIT {

    @Autowired private ServiceRepository serviceRepository;
    @Autowired private ServiceHistoryRepository historyRepository;
    @Autowired private TeamRepository teamRepository;
    @Autowired private AuditEventPort auditEventPort;
    @Autowired private NamedParameterJdbcTemplate jdbc;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> PostgresTestSupport.POSTGRES.getJdbcUrl() + "&currentSchema=registry");
        registry.add("spring.datasource.username", PostgresTestSupport.POSTGRES::getUsername);
        registry.add("spring.datasource.password", PostgresTestSupport.POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KafkaTestSupport.KAFKA::getBootstrapServers);
    }

    // ── services ──────────────────────────────────────────────────────────────

    @Test
    void findAccessibleReturnsOnlyLiveServicesOfTheUsersTeamsInTheTenant() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID myTeam = team(tenant);
        UUID otherTeam = team(tenant);
        UUID user = UUID.randomUUID();
        member(tenant, myTeam, user);
        UUID mine = service(tenant, myTeam);
        UUID theirs = service(tenant, otherTeam);
        UUID orphan = service(tenant, null);
        UUID deleted = service(tenant, myTeam);
        serviceRepository.softDelete(tenant, deleted);
        UUID otherTenant = UUID.randomUUID();
        UUID foreign = service(otherTenant, myTeam);

        AtomicReference<Set<UUID>> result = new AtomicReference<>();
        int statements = SqlStatementCounter.count(() -> result.set(serviceRepository.findAccessible(tenant, user,
                List.of(mine, theirs, orphan, deleted, foreign, UUID.randomUUID()))));

        assertThat(result.get()).containsExactly(mine);
        assertThat(statements).isEqualTo(1);
        assertThat(serviceRepository.findAccessible(tenant, user, List.of())).isEmpty();
    }

    @Test
    void findByIdsReturnsOnlyLiveServicesOfTheTenant() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID a = service(tenant, null);
        UUID b = service(tenant, null);
        UUID deleted = service(tenant, null);
        serviceRepository.softDelete(tenant, deleted);
        UUID foreign = service(UUID.randomUUID(), null);

        assertThat(serviceRepository.findByIds(tenant, List.of(a, b, deleted, foreign, UUID.randomUUID())))
                .extracting(Service::id).containsExactlyInAnyOrder(a, b);
        assertThat(serviceRepository.findByIds(tenant, List.of())).isEmpty();
    }

    @Test
    void findByTeamReturnsOnlyLiveServicesOfThatTeam() {
        UUID tenant = UUID.randomUUID();
        UUID team = team(tenant);
        UUID owned = service(tenant, team);
        service(tenant, team(tenant));
        service(tenant, null);
        UUID deleted = service(tenant, team);
        serviceRepository.softDelete(tenant, deleted);

        assertThat(serviceRepository.findByTeam(tenant, team)).extracting(Service::id).containsExactly(owned);
    }

    @Test
    void updateTeamSetsAndClearsTheOwnerInOneStatementWithinTheTenant() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID team = team(tenant);
        UUID a = service(tenant, null);
        UUID b = service(tenant, null);
        UUID untouched = service(tenant, null);
        UUID foreignTenant = UUID.randomUUID();
        UUID foreign = service(foreignTenant, null);
        Instant at = Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);

        int set = SqlStatementCounter.count(() -> serviceRepository.updateTeam(tenant, List.of(a, b, foreign), team, at));

        assertThat(set).isEqualTo(1);
        assertThat(teamOf(a)).isEqualTo(team);
        assertThat(teamOf(b)).isEqualTo(team);
        assertThat(teamOf(untouched)).isNull();
        assertThat(teamOf(foreign)).isNull();
        assertThat(serviceRepository.findById(tenant, a).orElseThrow().updatedAt()).isEqualTo(at);

        serviceRepository.updateTeam(tenant, List.of(a), null, at);
        assertThat(teamOf(a)).isNull();
        assertThat(teamOf(b)).isEqualTo(team);

        assertThat(SqlStatementCounter.count(() -> serviceRepository.updateTeam(tenant, List.of(), team, at))).isZero();
    }

    // ── history ───────────────────────────────────────────────────────────────

    @Test
    void historySaveAllInsertsEverySnapshotInOneStatement() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID a = service(tenant, null);
        UUID b = service(tenant, null);
        UUID by = UUID.randomUUID();
        Instant at = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        List<ServiceSnapshot> snapshots = List.of(
                new ServiceSnapshot(UUID.randomUUID(), a, tenant, "{\"name\":\"a\"}", by, at),
                new ServiceSnapshot(UUID.randomUUID(), b, tenant, "{\"name\":\"b\"}", by, at),
                new ServiceSnapshot(UUID.randomUUID(), b, tenant, "{\"name\":\"b2\"}", null, at));

        int statements = SqlStatementCounter.count(() -> historyRepository.saveAll(snapshots));

        assertThat(statements).isEqualTo(1);
        assertThat(historyRepository.findByServiceId(tenant, a, 10, 0)).extracting(ServiceSnapshot::id)
                .contains(snapshots.get(0).id());
        assertThat(historyRepository.findByServiceId(tenant, b, 10, 0)).extracting(ServiceSnapshot::id)
                .contains(snapshots.get(1).id(), snapshots.get(2).id());
        assertThat(SqlStatementCounter.count(() -> historyRepository.saveAll(List.of()))).isZero();
    }

    // ── team members ──────────────────────────────────────────────────────────

    @Test
    void addMembersInsertsInOneStatementAndSkipsExistingMembers() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID team = team(tenant);
        UUID existing = UUID.randomUUID();
        member(tenant, team, existing);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();

        int statements = SqlStatementCounter.count(() ->
                teamRepository.addMembers(tenant, team, List.of(existing, a, b), Instant.now()));

        assertThat(statements).isEqualTo(1);
        assertThat(teamRepository.findMembers(tenant, team)).extracting(TeamMember::userId)
                .containsExactlyInAnyOrder(existing, a, b);
        assertThat(teamRepository.findMembers(tenant, team)).allMatch(m -> m.tenantId().equals(tenant));
        assertThat(SqlStatementCounter.count(() -> teamRepository.addMembers(tenant, team, List.of(), Instant.now())))
                .isZero();
    }

    @Test
    void removeMembersDeletesOnlyTheListedUsersOfThatTeamInOneStatement() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID team = team(tenant);
        UUID otherTeam = team(tenant);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID kept = UUID.randomUUID();
        member(tenant, team, a);
        member(tenant, team, b);
        member(tenant, team, kept);
        member(tenant, otherTeam, a);

        int statements = SqlStatementCounter.count(() -> teamRepository.removeMembers(tenant, team, List.of(a, b)));

        assertThat(statements).isEqualTo(1);
        assertThat(teamRepository.findMembers(tenant, team)).extracting(TeamMember::userId).containsExactly(kept);
        assertThat(teamRepository.findMembers(tenant, otherTeam)).extracting(TeamMember::userId).containsExactly(a);
        assertThat(SqlStatementCounter.count(() -> teamRepository.removeMembers(tenant, team, List.of()))).isZero();
    }

    // ── audit ─────────────────────────────────────────────────────────────────

    @Test
    void recordAllInsertsEveryEventInOneStatementAndIgnoresRepeats() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID entity = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        AuditEvent added = AuditEvent.of(tenant, AuditKind.TEAM, entity, "member_added", Actor.user(user),
                Map.of("userId", user));
        AuditEvent removed = AuditEvent.of(tenant, AuditKind.TEAM, entity, "member_removed", Actor.SYSTEM, Map.of());

        int statements = SqlStatementCounter.count(() -> auditEventPort.recordAll(List.of(added, removed)));
        auditEventPort.recordAll(List.of(added));

        assertThat(statements).isEqualTo(1);
        assertThat(jdbc.queryForList("""
                SELECT action FROM audit_events WHERE tenant_id = :t AND entity_id = :e ORDER BY action
                """, new MapSqlParameterSource().addValue("t", tenant).addValue("e", entity), String.class))
                .containsExactly("member_added", "member_removed");
        assertThat(jdbc.queryForObject("""
                SELECT payload->>'userId' FROM audit_events WHERE event_id = :id
                """, new MapSqlParameterSource("id", added.eventId()), String.class)).isEqualTo(user.toString());
        assertThat(SqlStatementCounter.count(() -> auditEventPort.recordAll(List.of()))).isZero();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private UUID team(UUID tenantId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO teams (id, tenant_id, name) VALUES (:id, :tenantId, :name)",
                new MapSqlParameterSource().addValue("id", id).addValue("tenantId", tenantId)
                        .addValue("name", "team-" + id));
        return id;
    }

    private void member(UUID tenantId, UUID teamId, UUID userId) {
        jdbc.update("INSERT INTO team_members (tenant_id, team_id, user_id) VALUES (:tenantId, :teamId, :userId)",
                new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("teamId", teamId)
                        .addValue("userId", userId));
    }

    private UUID service(UUID tenantId, UUID teamId) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        serviceRepository.save(new Service(id, tenantId, "svc-" + id, null, teamId, null, null, null,
                ServiceHealthStatus.UNKNOWN, null, now, now, null,
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null));
        return id;
    }

    private UUID teamOf(UUID serviceId) {
        return jdbc.queryForObject("SELECT team_id FROM services WHERE id = :id",
                new MapSqlParameterSource("id", serviceId), UUID.class);
    }
}
