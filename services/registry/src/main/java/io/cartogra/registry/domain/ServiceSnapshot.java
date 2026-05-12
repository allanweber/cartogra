package io.cartogra.registry.domain;

import java.time.Instant;
import java.util.UUID;

public record ServiceSnapshot(
        UUID id,
        UUID serviceId,
        UUID tenantId,
        String snapshot,
        UUID changedBy,
        Instant changedAt
) {}
