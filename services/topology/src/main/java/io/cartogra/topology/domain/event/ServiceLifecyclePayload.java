package io.cartogra.topology.domain.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * Subset of Registry's {@code Service} record that Topology cares about — deserialized
 * straight off {@code cartogra.registry.service.{registered,updated,deleted}}, whose
 * payload is the full Service record. {@code ignoreUnknown} tolerates every field Topology
 * doesn't need (repository info, health endpoint, plan fields, ...) without the two
 * services having to keep a shared payload contract in lockstep.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
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
