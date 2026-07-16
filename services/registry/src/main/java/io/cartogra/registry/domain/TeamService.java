package io.cartogra.registry.domain;

import io.cartogra.common.api.PageResult;
import io.cartogra.registry.infrastructure.kafka.TeamLifecycleEventProducer;
import io.cartogra.registry.repository.AdvisoryLockRepository;
import io.cartogra.registry.repository.TeamRepository;
import io.cartogra.registry.domain.exception.DuplicateTeamNameException;
import io.cartogra.registry.domain.exception.PlanLimitExceededException;
import io.cartogra.registry.domain.exception.TeamNotFoundException;
import org.jspecify.annotations.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class TeamService {

    private final TeamRepository teamRepository;
    private final TeamLifecycleEventProducer eventProducer;
    private final PlanLimitService planLimitService;
    private final AdvisoryLockRepository advisoryLockRepository;

    public TeamService(TeamRepository teamRepository, TeamLifecycleEventProducer eventProducer,
                        PlanLimitService planLimitService, AdvisoryLockRepository advisoryLockRepository) {
        this.teamRepository = teamRepository;
        this.eventProducer = eventProducer;
        this.planLimitService = planLimitService;
        this.advisoryLockRepository = advisoryLockRepository;
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'TEAM_OWNER')")
    @Transactional
    public Team create(UUID tenantId, String name, @Nullable UUID requestedBy) {
        if (teamRepository.existsByName(tenantId, name, null)) {
            throw new DuplicateTeamNameException(name);
        }
        long lockKey = AdvisoryLockRepository.lockKey("cartogra.registry.team-create", tenantId);
        if (!advisoryLockRepository.tryAcquireLock(lockKey)) {
            throw new IllegalStateException("Could not acquire plan-limit lock for tenant " + tenantId);
        }
        try {
            int maxTeams = planLimitService.getLimits(tenantId).maxTeams();
            if (maxTeams != PlanLimits.UNLIMITED && teamRepository.count(tenantId) >= maxTeams) {
                throw new PlanLimitExceededException("teams", maxTeams);
            }
            Instant now = Instant.now();
            Team saved = teamRepository.save(new Team(UUID.randomUUID(), tenantId, name, now, now, null));
            eventProducer.publishCreated(saved);
            if (requestedBy != null && !isAdmin()) {
                teamRepository.addMember(new TeamMember(UUID.randomUUID(), tenantId, saved.id(), requestedBy, now));
            }
            return saved;
        } finally {
            advisoryLockRepository.releaseLock(lockKey);
        }
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'TEAM_OWNER')")
    @Transactional
    public Team update(UUID tenantId, UUID teamId, String name, @Nullable UUID requestedBy) {
        requireAdminOrOwningTeamOwner(tenantId, teamId, requestedBy);
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

    @PreAuthorize("hasAnyRole('ADMIN', 'TEAM_OWNER')")
    @Transactional
    public void delete(UUID tenantId, UUID teamId, @Nullable UUID requestedBy) {
        requireAdminOrOwningTeamOwner(tenantId, teamId, requestedBy);
        Team existing = teamRepository.findById(tenantId, teamId)
                .orElseThrow(() -> new TeamNotFoundException(teamId));
        Instant deletedAt = Instant.now();
        teamRepository.softDelete(tenantId, teamId);
        eventProducer.publishDeleted(new Team(
                existing.id(), existing.tenantId(), existing.name(),
                existing.createdAt(), existing.updatedAt(), deletedAt));
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'TEAM_OWNER')")
    @Transactional
    public TeamMember addMember(UUID tenantId, UUID teamId, UUID userId, @Nullable UUID requestedBy) {
        requireAdminOrOwningTeamOwner(tenantId, teamId, requestedBy);
        teamRepository.findById(tenantId, teamId).orElseThrow(() -> new TeamNotFoundException(teamId));
        if (teamRepository.isMember(tenantId, teamId, userId)) {
            return teamRepository.findMembers(tenantId, teamId).stream()
                    .filter(m -> m.userId().equals(userId))
                    .findFirst()
                    .orElseThrow();
        }
        return teamRepository.addMember(new TeamMember(UUID.randomUUID(), tenantId, teamId, userId, Instant.now()));
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'TEAM_OWNER')")
    @Transactional
    public void removeMember(UUID tenantId, UUID teamId, UUID userId, @Nullable UUID requestedBy) {
        requireAdminOrOwningTeamOwner(tenantId, teamId, requestedBy);
        teamRepository.findById(tenantId, teamId).orElseThrow(() -> new TeamNotFoundException(teamId));
        teamRepository.removeMember(tenantId, teamId, userId);
    }

    public List<TeamMember> listMembers(UUID tenantId, UUID teamId) {
        teamRepository.findById(tenantId, teamId).orElseThrow(() -> new TeamNotFoundException(teamId));
        return teamRepository.findMembers(tenantId, teamId);
    }

    public Team get(UUID tenantId, UUID teamId) {
        return teamRepository.findById(tenantId, teamId)
                .orElseThrow(() -> new TeamNotFoundException(teamId));
    }

    public Set<UUID> myTeamIds(UUID tenantId, @Nullable UUID userId) {
        if (userId == null) {
            return Set.of();
        }
        return teamRepository.findTeamIdsByMember(tenantId, userId);
    }

    public PageResult<Team> list(UUID tenantId, int limit, int offset) {
        List<Team> items = teamRepository.findAll(tenantId, limit, offset);
        long total = teamRepository.count(tenantId);
        return PageResult.of(items, total, limit, offset);
    }

    private void requireAdminOrOwningTeamOwner(UUID tenantId, UUID teamId, @Nullable UUID requestedBy) {
        if (isAdmin()) {
            return;
        }
        if (requestedBy == null || !teamRepository.isMember(tenantId, teamId, requestedBy)) {
            throw new AccessDeniedException(
                    "Only ADMIN or an owner who is a member of this team may perform this action");
        }
    }

    private boolean isAdmin() {
        return SecurityContextHolder.getContext().getAuthentication() != null
                && SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                        .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
    }
}
