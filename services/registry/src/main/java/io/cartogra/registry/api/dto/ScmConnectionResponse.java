package io.cartogra.registry.api.dto;

import java.time.Instant;
import java.util.UUID;

public record ScmConnectionResponse(
        UUID id,
        UUID tenantId,
        String provider,
        String config,
        Instant createdAt,
        Instant updatedAt
) {}
