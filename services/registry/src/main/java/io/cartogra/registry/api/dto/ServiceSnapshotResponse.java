package io.cartogra.registry.api.dto;

import io.cartogra.registry.domain.ServiceSnapshot;

import java.time.Instant;
import java.util.UUID;

public record ServiceSnapshotResponse(
        UUID id,
        UUID serviceId,
        UUID tenantId,
        String snapshot,
        UUID changedBy,
        Instant changedAt
) {
    public static ServiceSnapshotResponse from(ServiceSnapshot snap) {
        return new ServiceSnapshotResponse(
                snap.id(), snap.serviceId(), snap.tenantId(), snap.snapshot(),
                snap.changedBy(), snap.changedAt());
    }
}
