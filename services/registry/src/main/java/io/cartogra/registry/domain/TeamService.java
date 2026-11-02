package io.cartogra.registry.domain;

import io.cartogra.common.api.PageResult;
import io.cartogra.common.audit.AuditEvent;
import io.cartogra.common.audit.AuditEventPort;
import io.cartogra.common.identity.Actor;
import io.cartogra.registry.infrastructure.kafka.TeamLifecycleEventProducer;
import io.cartogra.registry.repository.TeamRepository;
import io.cartogra.registry.domain.exception.DuplicateTeamNameException;
import io.cartogra.registry.domain.exception.PlanLimitExceededException;
import io.cartogra.registry.domain.exception.TeamNotFoundException;
import io.cartogra.web.lock.AdvisoryLockRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class TeamService {

    private final TeamRepository teamRepository;
    private final TeamLifecycleEventProducer eventProducer;
    private final PlanLimitService planLimitService;
    private final AdvisoryLockRepository advisoryLockRepository;
    private final AuditEventPort auditEventPort;

    public TeamService(TeamRepository teamRepository, TeamLifecycleEventProducer eventProducer,
                        PlanLimitService planLimitService, AdvisoryLockRepository advisoryLockRepository,
                        AuditEventPort auditEventPort) {
        this.teamRepository = teamRepository;
        this.eventProducer = eventProducer;
        this.planLimitService = planLimitService;
        this.advisoryLockRepository = advisoryLockRepository;
        this.auditEventPort = auditEventPort;
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'TEAM_OWNER')")
    @Transactional
    public Team create(UUID tenantId, Actor actor, String name) {
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
            audit(saved, "created", actor, Map.of("name", name));
            eventProducer.publishCreated(saved);
            if (actor.userId() != null && !actor.admin()) {
                teamRepository.addMember(new TeamMember(UUID.randomUUID(), tenantId, saved.id(), actor.userId(), now));
            }
            return saved;
        } finally {
            advisoryLockRepository.releaseLock(lockKey);
        }
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'TEAM_OWNER')")
    @Transactional
    public Team update(UUID tenantId, Actor actor, UUID teamId, String name) {
        requireAdminOrOwningTeamOwner(tenantId, teamId, actor);
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
        audit(saved, "updated", actor, Map.of("name", name,
                "changes", Map.of("name", Map.of("from", existing.name(), "to", name))));
        eventProducer.publishUpdated(saved);
        return saved;
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'TEAM_OWNER')")
    @Transactional
    public void delete(UUID tenantId, Actor actor, UUID teamId) {
        requireAdminOrOwningTeamOwner(tenantId, teamId, actor);
        Team existing = teamRepository.findById(tenantId, teamId)
                .orElseThrow(() -> new TeamNotFoundException(teamId));
        Instant deletedAt = Instant.now();
        teamRepository.softDelete(tenantId, teamId);
        audit(existing, "deleted", actor, Map.of("name", existing.name()));
        eventProducer.publishDeleted(new Team(
                existing.id(), existing.tenantId(), existing.name(),
                existing.createdAt(), existing.updatedAt(), deletedAt));
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'TEAM_OWNER')")
    @Transactional
    public TeamMember addMember(UUID tenantId, Actor actor, UUID teamId, UUID userId) {
        requireAdminOrOwningTeamOwner(tenantId, teamId, actor);
        Team team = teamRepository.findById(tenantId, teamId).orElseThrow(() -> new TeamNotFoundException(teamId));
        if (teamRepository.isMember(tenantId, teamId, userId)) {
            return teamRepository.findMembers(tenantId, teamId).stream()
                    .filter(m -> m.userId().equals(userId))
                    .findFirst()
                    .orElseThrow();
        }
        TeamMember member = teamRepository.addMember(
                new TeamMember(UUID.randomUUID(), tenantId, teamId, userId, Instant.now()));
        audit(team, "member_added", actor, Map.of("name", team.name(), "userId", userId));
        return member;
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'TEAM_OWNER')")
    @Transactional
    public void removeMember(UUID tenantId, Actor actor, UUID teamId, UUID userId) {
        requireAdminOrOwningTeamOwner(tenantId, teamId, actor);
        Team team = teamRepository.findById(tenantId, teamId).orElseThrow(() -> new TeamNotFoundException(teamId));
        if (!teamRepository.isMember(tenantId, teamId, userId)) {
            return;
        }
        teamRepository.removeMember(tenantId, teamId, userId);
        audit(team, "member_removed", actor, Map.of("name", team.name(), "userId", userId));
    }

    public List<TeamMember> listMembers(UUID tenantId, UUID teamId) {
        teamRepository.findById(tenantId, teamId).orElseThrow(() -> new TeamNotFoundException(teamId));
        return teamRepository.findMembers(tenantId, teamId);
    }

    public Team get(UUID tenantId, UUID teamId) {
        return teamRepository.findById(tenantId, teamId)
                .orElseThrow(() -> new TeamNotFoundException(teamId));
    }

    public Set<UUID> myTeamIds(UUID tenantId, Actor actor) {
        UUID userId = actor.userId();
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

    private void audit(Team team, String action, Actor actor, Map<String, Object> payload) {
        auditEventPort.record(AuditEvent.of(team.tenantId(), "team", team.id(), action, actor, payload));
    }

    private void requireAdminOrOwningTeamOwner(UUID tenantId, UUID teamId, Actor actor) {
        if (actor.admin()) {
            return;
        }
        if (actor.userId() == null || !teamRepository.isMember(tenantId, teamId, actor.userId())) {
            throw new AccessDeniedException(
                    "Only ADMIN or an owner who is a member of this team may perform this action");
        }
    }

}
