package io.cartogra.registry.application.usecase;

import io.cartogra.registry.application.dto.CreateTeamCommand;
import io.cartogra.registry.domain.Team;

public interface CreateTeamUseCase {
    Team execute(CreateTeamCommand command);
}
