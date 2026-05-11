package io.cartogra.registry.domain;

import java.time.Instant;
import java.util.UUID;

public record Team(
        UUID id,
        UUID tenantId,
        String name,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt
) {
    public boolean isDeleted() {
        return deletedAt != null;
    }
}
