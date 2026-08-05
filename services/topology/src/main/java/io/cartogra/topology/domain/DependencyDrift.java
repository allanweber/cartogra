package io.cartogra.topology.domain;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

public record DependencyDrift(
        UUID id,
        UUID tenantId,
        UUID sourceServiceId,
        UUID targetServiceId,
        DriftType type,
        Instant detectedAt,
        @Nullable Instant resolvedAt,
        @Nullable UUID resolvedBy
) {
    public boolean isResolved() {
        return resolvedAt != null;
    }
}
