package io.cartogra.registry.api.dto;

import io.cartogra.registry.domain.TeamMember;

import java.time.Instant;
import java.util.UUID;

public record TeamMemberResponse(
        UUID id,
        UUID teamId,
        UUID userId,
        Instant createdAt
) {
    public static TeamMemberResponse from(TeamMember m) {
        return new TeamMemberResponse(m.id(), m.teamId(), m.userId(), m.createdAt());
    }
}
