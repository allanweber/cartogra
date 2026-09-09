package io.cartogra.topology.infrastructure.registry;

import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * The subset of Registry's {@code ServiceResponse} that {@code GET /internal/services}
 * needs to seed a graph node. Jackson 3's default {@code ObjectMapper} ignores unknown
 * properties, so the rest of that response (description, repo info, plan fields, ...) is
 * tolerated without a shared contract to keep in sync.
 */
public record RegistryServiceSnapshot(
        UUID id,
        UUID tenantId,
        String name,
        @Nullable UUID teamId,
        @Nullable String tier,
        String healthStatus
) {
}
