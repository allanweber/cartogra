package io.cartogra.ingestion.api.dto;

import io.cartogra.ingestion.domain.ScmConnection;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

public record ScmConnectionResponse(
        UUID id,
        UUID tenantId,
        String provider,
        String config,
        boolean syncScheduler,
        int pollIntervalMinutes,
        @Nullable Instant nextSyncAt,
        @Nullable Instant lastSyncAt,
        @Nullable String lastSyncStatus,
        boolean webhookEnabled,
        Instant createdAt,
        Instant updatedAt
) {
    public static ScmConnectionResponse from(ScmConnection c) {
        return new ScmConnectionResponse(
                c.id(), c.tenantId(), c.provider(), c.config(),
                c.syncScheduler(), c.pollIntervalMinutes(),
                c.nextSyncAt(), c.lastSyncAt(), c.lastSyncStatus(),
                c.webhookEnabled(), c.createdAt(), c.updatedAt());
    }
}
