package io.cartogra.topology.api.dto;

import io.cartogra.topology.domain.DependencyDrift;
import io.cartogra.topology.domain.DriftResolution;
import io.cartogra.topology.domain.DriftType;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

public record DriftResponse(
        UUID id,
        DriftType type,
        UUID sourceServiceId,
        UUID targetServiceId,
        Instant detectedAt,
        @Nullable Instant resolvedAt,
        @Nullable DriftResolution resolution
) {
    public static DriftResponse from(DependencyDrift drift) {
        return new DriftResponse(drift.id(), drift.type(), drift.sourceServiceId(), drift.targetServiceId(),
                drift.detectedAt(), drift.resolvedAt(), drift.resolution());
    }
}
