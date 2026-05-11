package io.cartogra.registry.api.dto;

import java.time.Instant;
import java.util.UUID;

public record TeamResponse(
        UUID id,
        UUID tenantId,
        String name,
        Instant createdAt,
        Instant updatedAt
) {}
