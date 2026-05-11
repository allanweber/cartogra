package io.cartogra.registry.api.mapper;

import io.cartogra.registry.api.dto.TeamResponse;
import io.cartogra.registry.domain.Team;

public final class TeamMapper {

    private TeamMapper() {}

    public static TeamResponse toResponse(Team t) {
        return new TeamResponse(t.id(), t.tenantId(), t.name(), t.createdAt(), t.updatedAt());
    }
}
