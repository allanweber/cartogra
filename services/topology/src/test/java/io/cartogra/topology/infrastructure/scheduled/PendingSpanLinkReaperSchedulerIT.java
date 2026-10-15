package io.cartogra.topology.infrastructure.scheduled;

import io.cartogra.topology.AbstractTopologyIT;
import io.cartogra.topology.domain.SpanKind;
import io.cartogra.topology.repository.PendingSpanLink;
import io.cartogra.topology.repository.PendingSpanLinkRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PendingSpanLinkReaperSchedulerIT extends AbstractTopologyIT {

    @Autowired
    PendingSpanLinkReaperScheduler reaper;

    @Autowired
    PendingSpanLinkRepository repository;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @Test
    void reapRemovesExpiredButKeepsLiveRows() {
        UUID tenant = UUID.randomUUID();
        repository.insert(new PendingSpanLink(UUID.randomUUID(), tenant, "t", "expired", null, SpanKind.CLIENT,
                "svc", "http", Instant.now().minusSeconds(5)));
        repository.insert(new PendingSpanLink(UUID.randomUUID(), tenant, "t", "live", null, SpanKind.CLIENT,
                "svc", "http", Instant.now().plusSeconds(300)));

        reaper.reap();

        assertThat(repository.findClientPartner(tenant, "t", "live")).isPresent();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pending_span_links WHERE tenant_id = :t",
                new MapSqlParameterSource("t", tenant), Long.class)).isEqualTo(1L);
    }
}
