package io.cartogra.registry.domain;

import io.cartogra.common.api.PageResult;
import io.cartogra.registry.repository.TeamRepository;
import io.cartogra.registry.domain.exception.DuplicateTeamNameException;
import io.cartogra.registry.domain.exception.TeamNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** All operations on the Team aggregate. */
@Service
public class TeamService {

    private final TeamRepository teamRepository;

    public TeamService(TeamRepository teamRepository) {
        this.teamRepository = teamRepository;
    }

    @Transactional
    public Team create(UUID tenantId, String name) {
        if (teamRepository.existsByName(tenantId, name, null)) {
            throw new DuplicateTeamNameException(name);
        }
        Instant now = Instant.now();
        return teamRepository.save(new Team(UUID.randomUUID(), tenantId, name, now, now, null));
    }

    @Transactional
    public Team update(UUID tenantId, UUID teamId, String name) {
        Team existing = teamRepository.findById(tenantId, teamId)
                .orElseThrow(() -> new TeamNotFoundException(teamId));

        if (!existing.name().equalsIgnoreCase(name)
                && teamRepository.existsByName(tenantId, name, teamId)) {
            throw new DuplicateTeamNameException(name);
        }
        return teamRepository.save(new Team(
                existing.id(), existing.tenantId(), name,
                existing.createdAt(), Instant.now(), null));
    }

    @Transactional
    public void delete(UUID tenantId, UUID teamId) {
        teamRepository.findById(tenantId, teamId)
                .orElseThrow(() -> new TeamNotFoundException(teamId));
        teamRepository.softDelete(tenantId, teamId);
    }

    public Team get(UUID tenantId, UUID teamId) {
        return teamRepository.findById(tenantId, teamId)
                .orElseThrow(() -> new TeamNotFoundException(teamId));
    }

    public PageResult<Team> list(UUID tenantId, int limit, int offset) {
        List<Team> items = teamRepository.findAll(tenantId, limit, offset);
        long total = teamRepository.count(tenantId);
        return PageResult.of(items, total, limit, offset);
    }
}
