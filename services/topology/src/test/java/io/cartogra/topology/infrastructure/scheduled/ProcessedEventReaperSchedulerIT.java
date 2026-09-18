package io.cartogra.topology.infrastructure.scheduled;

import io.cartogra.topology.AbstractTopologyIT;
import io.cartogra.topology.repository.ProcessedEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessedEventReaperSchedulerIT extends AbstractTopologyIT {

    @DynamicPropertySource
    static void reaperProps(DynamicPropertyRegistry registry) {
        registry.add("topology.processed-events.retention", () -> "PT1H");
    }

    @Autowired
    ProcessedEventReaperScheduler reaper;

    @Autowired
    ProcessedEventRepository processedEventRepository;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @Test
    void reapDeletesOnlyRowsOlderThanRetention() {
        UUID tenantId = UUID.randomUUID();
        UUID oldEventId = UUID.randomUUID();
        UUID recentEventId = UUID.randomUUID();
        processedEventRepository.markProcessed(tenantId, oldEventId);
        processedEventRepository.markProcessed(tenantId, recentEventId);
        backdate(tenantId, oldEventId, Instant.now().minus(Duration.ofHours(2)));

        reaper.reap();

        assertThat(processedEventRepository.markProcessed(tenantId, oldEventId)).isTrue();
        assertThat(processedEventRepository.markProcessed(tenantId, recentEventId)).isFalse();
    }

    private void backdate(UUID tenantId, UUID eventId, Instant processedAt) {
        jdbc.update(
                "UPDATE processed_events SET processed_at = :processedAt WHERE tenant_id = :tenantId AND event_id = :eventId",
                new MapSqlParameterSource()
                        .addValue("processedAt", java.sql.Timestamp.from(processedAt))
                        .addValue("tenantId", tenantId)
                        .addValue("eventId", eventId));
    }
}
