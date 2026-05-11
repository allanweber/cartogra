package io.cartogra.registry.application.usecase.impl;

import io.cartogra.registry.application.dto.CreateTeamCommand;
import io.cartogra.registry.application.repository.TeamRepository;
import io.cartogra.registry.application.usecase.CreateTeamUseCase;
import io.cartogra.registry.domain.Team;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Component
public class CreateTeamUseCaseImpl implements CreateTeamUseCase {

    private final TeamRepository teamRepository;

    public CreateTeamUseCaseImpl(TeamRepository teamRepository) {
        this.teamRepository = teamRepository;
    }

    @Override
    @Transactional
    public Team execute(CreateTeamCommand command) {
        if (teamRepository.existsByName(command.tenantId(), command.name(), null)) {
            throw new DuplicateKeyException("A team named '" + command.name() + "' already exists in this tenant");
        }
        Instant now = Instant.now();
        return teamRepository.save(new Team(UUID.randomUUID(), command.tenantId(), command.name(), now, now, null));
    }
}
