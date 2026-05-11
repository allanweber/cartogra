package io.cartogra.registry.application.usecase;

import io.cartogra.registry.domain.Team;

import java.util.UUID;

public interface FindTeamUseCase {
    Team execute(UUID tenantId, UUID teamId);
}
