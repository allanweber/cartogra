package io.cartogra.topology.domain;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

public record UnresolvedSpanService(
        UUID id,
        UUID tenantId,
        String name,
        Instant firstSeenAt,
        Instant lastSeenAt,
        long seenCount,
        @Nullable Instant deletedAt,
        @Nullable UnresolvedResolution resolution
) {
    public boolean isResolved() {
        return deletedAt != null;
    }
}
