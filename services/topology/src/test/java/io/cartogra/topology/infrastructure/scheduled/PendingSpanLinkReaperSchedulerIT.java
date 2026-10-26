package io.cartogra.topology.infrastructure.scheduled;

import io.cartogra.topology.AbstractTopologyIT;
import io.cartogra.topology.domain.DependencyProtocol;
import io.cartogra.topology.domain.SpanKind;
import io.cartogra.topology.repository.MessagingEndpoint;
import io.cartogra.topology.repository.PendingSpanLink;
import io.cartogra.topology.repository.PendingSpanLinkRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
        repository.park(tenant, List.of(
                new PendingSpanLink(UUID.randomUUID(), tenant, "t", "expired", null, SpanKind.CLIENT,
                        "svc", DependencyProtocol.HTTP, Instant.now().minusSeconds(5)),
                new PendingSpanLink(UUID.randomUUID(), tenant, "t", "live", null, SpanKind.CLIENT,
                        "svc", DependencyProtocol.HTTP, Instant.now().plusSeconds(300))));

        reaper.reap();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM pending_span_links WHERE tenant_id = :t",
                new MapSqlParameterSource("t", tenant), Long.class)).isEqualTo(1L);
        assertThat(repository.takeClientPartner(tenant, "t", "live")).isPresent();
    }

    @Test
    void reapRemovesIdleMessagingEndpointsButKeepsLiveOnes() {
        UUID tenant = UUID.randomUUID();
        repository.joinMessagingEndpoints(tenant,
                List.of(new MessagingEndpoint("orders.paid", SpanKind.PRODUCER, "checkout")),
                Instant.now().minusSeconds(5));
        repository.joinMessagingEndpoints(tenant,
                List.of(new MessagingEndpoint("orders.paid", SpanKind.CONSUMER, "payments")),
                Instant.now().plus(Duration.ofHours(24)));

        reaper.reap();

        assertThat(jdbc.queryForObject("SELECT service_name FROM pending_span_links WHERE tenant_id = :t",
                new MapSqlParameterSource("t", tenant), String.class)).isEqualTo("payments");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pending_span_links WHERE tenant_id = :t",
                new MapSqlParameterSource("t", tenant), Long.class)).isEqualTo(1L);
    }
}
