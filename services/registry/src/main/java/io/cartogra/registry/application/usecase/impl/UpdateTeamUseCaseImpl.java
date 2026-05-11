package io.cartogra.registry.application.usecase.impl;

import io.cartogra.registry.application.dto.UpdateTeamCommand;
import io.cartogra.registry.application.repository.TeamRepository;
import io.cartogra.registry.application.usecase.UpdateTeamUseCase;
import io.cartogra.registry.domain.Team;
import io.cartogra.registry.domain.exception.TeamNotFoundException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Component
public class UpdateTeamUseCaseImpl implements UpdateTeamUseCase {

    private final TeamRepository teamRepository;

    public UpdateTeamUseCaseImpl(TeamRepository teamRepository) {
        this.teamRepository = teamRepository;
    }

    @Override
    @Transactional
    public Team execute(UpdateTeamCommand command) {
        Team existing = teamRepository.findById(command.tenantId(), command.teamId())
                .orElseThrow(() -> new TeamNotFoundException(command.teamId()));

        if (!existing.name().equalsIgnoreCase(command.name())
                && teamRepository.existsByName(command.tenantId(), command.name(), command.teamId())) {
            throw new DuplicateKeyException("A team named '" + command.name() + "' already exists in this tenant");
        }

        return teamRepository.save(new Team(
                existing.id(), existing.tenantId(), command.name(),
                existing.createdAt(), Instant.now(), null
        ));
    }
}
