package io.cartogra.topology.infrastructure.jdbc;

import io.cartogra.topology.AbstractTopologyIT;
import io.cartogra.topology.repository.ProcessedEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcProcessedEventRepositoryIT extends AbstractTopologyIT {

    @Autowired
    private ProcessedEventRepository repository;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Test
    void firstMarkReturnsTrue() {
        UUID tenantId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        assertThat(repository.markProcessed(tenantId, eventId)).isTrue();
    }

    @Test
    void replayedMarkReturnsFalse() {
        UUID tenantId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        repository.markProcessed(tenantId, eventId);

        assertThat(repository.markProcessed(tenantId, eventId)).isFalse();
    }

    @Test
    void sameEventIdUnderDifferentTenantIsNotADuplicate() {
        UUID eventId = UUID.randomUUID();

        assertThat(repository.markProcessed(UUID.randomUUID(), eventId)).isTrue();
        assertThat(repository.markProcessed(UUID.randomUUID(), eventId)).isTrue();
    }

    @Test
    void deleteOlderThanRemovesOnlyRowsPastTheThreshold() {
        UUID tenantId = UUID.randomUUID();
        UUID oldEventId = UUID.randomUUID();
        UUID recentEventId = UUID.randomUUID();
        repository.markProcessed(tenantId, oldEventId);
        repository.markProcessed(tenantId, recentEventId);
        backdate(tenantId, oldEventId, Instant.now().minus(Duration.ofDays(40)));

        int deleted = repository.deleteOlderThan(Instant.now().minus(Duration.ofDays(30)));

        assertThat(deleted).isEqualTo(1);
        assertThat(repository.markProcessed(tenantId, oldEventId)).isTrue(); // gone, so this looks like a fresh event again
        assertThat(repository.markProcessed(tenantId, recentEventId)).isFalse(); // still there
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
