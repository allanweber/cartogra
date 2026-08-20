package io.cartogra.topology.domain.event;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * Subset of Registry's {@code Service} record that Topology cares about — deserialized
 * straight off {@code cartogra.registry.service.{registered,updated,deleted}}, whose
 * payload is the full Service record. Jackson 3's default {@code ObjectMapper} ignores
 * unknown properties, so every field Topology doesn't need (repository info, health
 * endpoint, plan fields, ...) is tolerated without the two services having to keep a
 * shared payload contract in lockstep.
 */
public record ServiceLifecyclePayload(
        UUID id,
        UUID tenantId,
        String name,
        @Nullable UUID teamId,
        @Nullable String tier,
        String healthStatus,
        @Nullable Instant deletedAt
) {
}
