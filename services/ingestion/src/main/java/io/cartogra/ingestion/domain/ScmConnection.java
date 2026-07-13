package io.cartogra.ingestion.domain;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

public record ScmConnection(
        UUID id,
        UUID tenantId,
        String provider,
        String config,
        boolean syncScheduler,
        int pollIntervalMinutes,
        @Nullable Instant nextSyncAt,
        @Nullable Instant lastSyncAt,
        @Nullable String lastSyncStatus,
        @Nullable String lastSyncError,
        boolean webhookEnabled,
        Instant createdAt,
        Instant updatedAt,
        @Nullable Instant deletedAt
) {
    public boolean isDeleted() {
        return deletedAt != null;
    }
}
