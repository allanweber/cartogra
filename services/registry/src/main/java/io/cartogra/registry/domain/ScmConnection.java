package io.cartogra.registry.domain;

import java.time.Instant;
import java.util.UUID;

public record ScmConnection(
        UUID id,
        UUID tenantId,
        String provider,
        String config,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt
) {
    public boolean isDeleted() {
        return deletedAt != null;
    }
}
