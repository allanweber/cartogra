package io.cartogra.registry.api.dto;

import io.cartogra.registry.domain.TeamMember;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

public record TeamMemberResponse(
        UUID id,
        UUID teamId,
        @Nullable UUID userId,
        Instant createdAt
) {
    public static TeamMemberResponse from(TeamMember m) {
        return new TeamMemberResponse(m.id(), m.teamId(), m.userId(), m.createdAt());
    }

    public TeamMemberResponse withoutUser() {
        return new TeamMemberResponse(id, teamId, null, createdAt);
    }
}
