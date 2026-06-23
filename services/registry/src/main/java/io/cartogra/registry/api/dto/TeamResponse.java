package io.cartogra.registry.api.dto;

import io.cartogra.registry.domain.Team;

import java.time.Instant;
import java.util.UUID;

public record TeamResponse(
        UUID id,
        UUID tenantId,
        String name,
        Instant createdAt,
        Instant updatedAt
) {
    public static TeamResponse from(Team t) {
        return new TeamResponse(t.id(), t.tenantId(), t.name(), t.createdAt(), t.updatedAt());
    }
}
