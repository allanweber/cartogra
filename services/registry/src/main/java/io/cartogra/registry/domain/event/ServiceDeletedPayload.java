package io.cartogra.registry.domain.event;

import java.time.Instant;
import java.util.UUID;

public record ServiceDeletedPayload(
        UUID serviceId,
        UUID tenantId,
        String name,
        Instant deletedAt
) {}
