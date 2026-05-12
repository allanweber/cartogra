package io.cartogra.registry.application.usecase.impl;

import io.cartogra.registry.application.repository.TeamRepository;
import io.cartogra.registry.application.usecase.FindTeamUseCase;
import io.cartogra.registry.domain.Team;
import io.cartogra.registry.domain.exception.TeamNotFoundException;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class FindTeamUseCaseImpl implements FindTeamUseCase {

    private final TeamRepository teamRepository;

    public FindTeamUseCaseImpl(TeamRepository teamRepository) {
        this.teamRepository = teamRepository;
    }

    @Override
    public Team execute(UUID tenantId, UUID teamId) {
        return teamRepository.findById(tenantId, teamId)
                .orElseThrow(() -> new TeamNotFoundException(teamId));
    }
}
