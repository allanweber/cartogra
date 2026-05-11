package io.cartogra.registry.application.usecase;

import io.cartogra.common.api.PageResult;
import io.cartogra.registry.domain.Team;

import java.util.UUID;

public interface ListTeamsUseCase {
    PageResult<Team> execute(UUID tenantId, int limit, int offset);
}
