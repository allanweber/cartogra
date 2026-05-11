package io.cartogra.registry.application.usecase;

import io.cartogra.registry.application.dto.UpdateTeamCommand;
import io.cartogra.registry.domain.Team;

public interface UpdateTeamUseCase {
    Team execute(UpdateTeamCommand command);
}
