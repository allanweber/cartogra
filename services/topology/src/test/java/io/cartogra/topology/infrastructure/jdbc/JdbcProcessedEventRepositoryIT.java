package io.cartogra.topology.infrastructure.jdbc;

import io.cartogra.topology.AbstractTopologyIT;
import io.cartogra.topology.repository.ProcessedEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcProcessedEventRepositoryIT extends AbstractTopologyIT {

    @Autowired
    private ProcessedEventRepository repository;

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
}
