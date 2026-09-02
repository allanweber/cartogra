package io.cartogra.topology.repository;

import org.jspecify.annotations.Nullable;

import java.util.UUID;

public record GraphNodeUpsert(
        UUID tenantId,
        UUID serviceId,
        String name,
        @Nullable UUID teamId,
        @Nullable String tier,
        String healthStatus
) {
}
