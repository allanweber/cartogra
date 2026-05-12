package io.cartogra.registry.api.dto;

import java.time.Instant;
import java.util.UUID;

public record ServiceSnapshotResponse(
        UUID id,
        UUID serviceId,
        UUID tenantId,
        String snapshot,
        UUID changedBy,
        Instant changedAt
) {}
