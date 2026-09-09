package io.cartogra.topology.domain;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * Local projection of a Registry service, kept in sync by {@code GraphNodeEventConsumer}.
 * Cross-context reference — {@code serviceId} is Registry's ID only, never hydrated.
 */
public record GraphNode(
        UUID id,
        UUID tenantId,
        UUID serviceId,
        String name,
        @Nullable UUID teamId,
        @Nullable String tier,
        String healthStatus,
        Instant createdAt,
        Instant updatedAt,
        @Nullable Instant deletedAt
) {
    public boolean isDeleted() {
        return deletedAt != null;
    }
}
