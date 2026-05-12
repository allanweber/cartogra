package io.cartogra.registry.application.usecase.impl;

import io.cartogra.registry.application.repository.TeamRepository;
import io.cartogra.registry.application.usecase.DeleteTeamUseCase;
import io.cartogra.registry.domain.exception.TeamNotFoundException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Component
public class DeleteTeamUseCaseImpl implements DeleteTeamUseCase {

    private final TeamRepository teamRepository;

    public DeleteTeamUseCaseImpl(TeamRepository teamRepository) {
        this.teamRepository = teamRepository;
    }

    @Override
    @Transactional
    public void execute(UUID tenantId, UUID teamId) {
        teamRepository.findById(tenantId, teamId)
                .orElseThrow(() -> new TeamNotFoundException(teamId));
        teamRepository.softDelete(tenantId, teamId);
    }
}
