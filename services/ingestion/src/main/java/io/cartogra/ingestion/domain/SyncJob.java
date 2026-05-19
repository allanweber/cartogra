package io.cartogra.ingestion.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

@Table("sync_jobs")
public record SyncJob(
        @Id UUID id,
        UUID tenantId,
        UUID connectionId,
        String providerType,
        String status,
        Instant startedAt,
        Instant completedAt,
        String errorMessage,
        Integer repositoriesSynced,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt
) {
    public static SyncJob create(UUID tenantId, UUID connectionId, String providerType) {
        Instant now = Instant.now();
        return new SyncJob(
                null, tenantId, connectionId, providerType,
                "PENDING", null, null, null, null,
                now, now, null
        );
    }
}
