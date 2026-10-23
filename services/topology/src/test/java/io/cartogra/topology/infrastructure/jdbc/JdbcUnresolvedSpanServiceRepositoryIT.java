package io.cartogra.topology.infrastructure.jdbc;

import io.cartogra.topology.AbstractTopologyIT;
import io.cartogra.topology.domain.UnresolvedResolution;
import io.cartogra.topology.domain.UnresolvedSpanService;
import io.cartogra.topology.repository.UnresolvedSpanServiceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcUnresolvedSpanServiceRepositoryIT extends AbstractTopologyIT {

    @Autowired
    UnresolvedSpanServiceRepository repository;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @Test
    void recordUpsertsOnCaseInsensitiveNameAndCounts() {
        UUID tenant = UUID.randomUUID();
        Instant first = Instant.now().minusSeconds(60);
        Instant second = Instant.now();

        repository.record(tenant, "Ghost", first, 100);
        repository.record(tenant, "ghost", second, 100);
        repository.record(tenant, "other", second, 100);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT name, seen_count FROM unresolved_span_services WHERE tenant_id = :t ORDER BY name",
                new MapSqlParameterSource("t", tenant));
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("name", "Ghost").containsEntry("seen_count", 2L);
        assertThat(rows.get(1)).containsEntry("seen_count", 1L);
    }

    @Test
    void newNamesStopAtTheCapButExistingOnesKeepCounting() {
        UUID tenant = UUID.randomUUID();
        Instant now = Instant.now();

        repository.record(tenant, "a", now, 2);
        repository.record(tenant, "b", now, 2);
        repository.record(tenant, "c", now, 2);
        repository.record(tenant, "A", now, 2);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT lower(name) AS name, seen_count FROM unresolved_span_services WHERE tenant_id = :t ORDER BY name",
                new MapSqlParameterSource("t", tenant));
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("name", "a").containsEntry("seen_count", 2L);
    }

    private UUID idOf(UUID tenant, String name) {
        return repository.findActive(tenant, 200, 0).stream()
                .filter(r -> r.name().equalsIgnoreCase(name)).findFirst().orElseThrow().id();
    }

    @Test
    void findActiveOrdersBySeenCountAndIsTenantScoped() {
        UUID tenant = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        Instant now = Instant.now();
        repository.record(tenant, "rare", now, 100);
        repository.record(tenant, "busy", now, 100);
        repository.record(tenant, "busy", now, 100);
        repository.record(tenant, "busy", now, 100);
        repository.record(tenant, "mid", now, 100);
        repository.record(tenant, "mid", now, 100);
        repository.record(other, "elsewhere", now, 100);

        assertThat(repository.findActive(tenant, 10, 0)).extracting(UnresolvedSpanService::name)
                .containsExactly("busy", "mid", "rare");
        assertThat(repository.findActive(tenant, 1, 1)).extracting(UnresolvedSpanService::name)
                .containsExactly("mid");
        assertThat(repository.countActive(tenant)).isEqualTo(3);
    }

    @Test
    void dismissedNameIsNeverRecordedAgain() {
        UUID tenant = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        repository.record(tenant, "stripe-gateway", Instant.now(), 100);
        UUID id = idOf(tenant, "stripe-gateway");

        assertThat(repository.dismiss(tenant, id, user, Instant.now())).isTrue();
        repository.record(tenant, "Stripe-Gateway", Instant.now(), 100);

        assertThat(repository.countActive(tenant)).isZero();
        UnresolvedSpanService dismissed = repository.findById(tenant, id).orElseThrow();
        assertThat(dismissed.resolution()).isEqualTo(UnresolvedResolution.DISMISSED);
        assertThat(dismissed.seenCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT resolved_by FROM unresolved_span_services WHERE id = :id",
                new MapSqlParameterSource("id", id), UUID.class)).isEqualTo(user);
    }

    @Test
    void dismissIsTenantScopedAndOnlyOnce() {
        UUID tenant = UUID.randomUUID();
        repository.record(tenant, "ghost", Instant.now(), 100);
        UUID id = idOf(tenant, "ghost");

        assertThat(repository.dismiss(UUID.randomUUID(), id, null, Instant.now())).isFalse();
        assertThat(repository.dismiss(tenant, id, null, Instant.now())).isTrue();
        assertThat(repository.dismiss(tenant, id, null, Instant.now())).isFalse();
        assertThat(repository.findById(UUID.randomUUID(), id)).isEmpty();
    }

    @Test
    void registeredNameLeavesTheListButCanBeRecordedAgain() {
        UUID tenant = UUID.randomUUID();
        repository.record(tenant, "Ledger", Instant.now(), 100);
        UUID id = idOf(tenant, "ledger");

        repository.markRegistered(tenant, "LEDGER", Instant.now());

        assertThat(repository.countActive(tenant)).isZero();
        assertThat(repository.findById(tenant, id).orElseThrow().resolution())
                .isEqualTo(UnresolvedResolution.REGISTERED);

        repository.record(tenant, "ledger", Instant.now(), 100);

        assertThat(repository.findActive(tenant, 10, 0)).singleElement()
                .satisfies(r -> assertThat(r.id()).isNotEqualTo(id));
    }

    @Test
    void markRegisteredIgnoresOtherTenantsAndUnknownNames() {
        UUID tenant = UUID.randomUUID();
        repository.record(tenant, "ghost", Instant.now(), 100);

        repository.markRegistered(UUID.randomUUID(), "ghost", Instant.now());
        repository.markRegistered(tenant, "other", Instant.now());

        assertThat(repository.countActive(tenant)).isEqualTo(1);
    }
}
