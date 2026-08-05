package io.cartogra.topology.domain;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

public record Dependency(
        UUID id,
        UUID tenantId,
        UUID sourceServiceId,
        UUID targetServiceId,
        DependencyType type,
        DependencyProtocol protocol,
        @Nullable String metadata,
        Instant createdAt,
        Instant updatedAt,
        @Nullable Instant deletedAt
) {
    public boolean isDeleted() {
        return deletedAt != null;
    }
}
