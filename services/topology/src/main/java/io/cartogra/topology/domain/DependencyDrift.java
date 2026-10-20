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
        @Nullable UUID resolvedBy,
        @Nullable DriftResolution resolution
) {
    public DependencyDrift(UUID id, UUID tenantId, UUID sourceServiceId, UUID targetServiceId, DriftType type,
            Instant detectedAt, @Nullable Instant resolvedAt, @Nullable UUID resolvedBy) {
        this(id, tenantId, sourceServiceId, targetServiceId, type, detectedAt, resolvedAt, resolvedBy,
                resolvedAt != null ? DriftResolution.AUTO : null);
    }

    public boolean isResolved() {
        return resolvedAt != null;
    }
}
