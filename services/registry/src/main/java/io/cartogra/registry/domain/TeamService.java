package io.cartogra.registry.domain;

import io.cartogra.common.api.PageResult;
import io.cartogra.registry.infrastructure.kafka.TeamLifecycleEventProducer;
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
    private final TeamLifecycleEventProducer eventProducer;

    public TeamService(TeamRepository teamRepository, TeamLifecycleEventProducer eventProducer) {
        this.teamRepository = teamRepository;
        this.eventProducer = eventProducer;
    }

    @Transactional
    public Team create(UUID tenantId, String name) {
        if (teamRepository.existsByName(tenantId, name, null)) {
            throw new DuplicateTeamNameException(name);
        }
        Instant now = Instant.now();
        Team saved = teamRepository.save(new Team(UUID.randomUUID(), tenantId, name, now, now, null));
        eventProducer.publishCreated(saved);
        return saved;
    }

    @Transactional
    public Team update(UUID tenantId, UUID teamId, String name) {
        Team existing = teamRepository.findById(tenantId, teamId)
                .orElseThrow(() -> new TeamNotFoundException(teamId));

        if (existing.name().equals(name)) {
            return existing;
        }

        if (!existing.name().equalsIgnoreCase(name)
                && teamRepository.existsByName(tenantId, name, teamId)) {
            throw new DuplicateTeamNameException(name);
        }
        Team saved = teamRepository.save(new Team(
                existing.id(), existing.tenantId(), name,
                existing.createdAt(), Instant.now(), null));
        eventProducer.publishUpdated(saved);
        return saved;
    }

    @Transactional
    public void delete(UUID tenantId, UUID teamId) {
        Team existing = teamRepository.findById(tenantId, teamId)
                .orElseThrow(() -> new TeamNotFoundException(teamId));
        Instant deletedAt = Instant.now();
        teamRepository.softDelete(tenantId, teamId);
        eventProducer.publishDeleted(new Team(
                existing.id(), existing.tenantId(), existing.name(),
                existing.createdAt(), existing.updatedAt(), deletedAt));
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
