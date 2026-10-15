package io.cartogra.topology.infrastructure.jdbc;

import io.cartogra.topology.AbstractTopologyIT;
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
}
