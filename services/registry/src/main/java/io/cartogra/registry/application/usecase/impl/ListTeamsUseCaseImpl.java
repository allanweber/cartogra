package io.cartogra.registry.application.usecase.impl;

import io.cartogra.common.api.PageResult;
import io.cartogra.registry.application.repository.TeamRepository;
import io.cartogra.registry.application.usecase.ListTeamsUseCase;
import io.cartogra.registry.domain.Team;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class ListTeamsUseCaseImpl implements ListTeamsUseCase {

    private final TeamRepository teamRepository;

    public ListTeamsUseCaseImpl(TeamRepository teamRepository) {
        this.teamRepository = teamRepository;
    }

    @Override
    public PageResult<Team> execute(UUID tenantId, int limit, int offset) {
        List<Team> items = teamRepository.findAll(tenantId, limit, offset);
        long total = teamRepository.count(tenantId);
        return PageResult.of(items, total, limit, offset);
    }
}
