package io.cartogra.topology.infrastructure.registry;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * The subset of Registry's {@code ServiceResponse} that {@code GET /internal/services}
 * needs to seed a graph node. {@code ignoreUnknown} tolerates the rest of that response
 * (description, repo info, plan fields, ...) without a shared contract to keep in sync.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RegistryServiceSnapshot(
        UUID id,
        UUID tenantId,
        String name,
        @Nullable UUID teamId,
        @Nullable String tier,
        String healthStatus
) {
}
