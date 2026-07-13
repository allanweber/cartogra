package io.cartogra.registry.domain;

import java.time.Instant;
import java.util.UUID;

public record TeamMember(
        UUID id,
        UUID tenantId,
        UUID teamId,
        UUID userId,
        Instant createdAt
) {
}
