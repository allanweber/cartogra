package io.cartogra.registry.domain;

import io.cartogra.common.audit.AuditKind;
import io.cartogra.common.identity.Roles;
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
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class TeamService {

    private final TeamRepository teamRepository;
    private final TeamLifecycleEventProducer eventProducer;
    private final PlanLimitService planLimitService;
    private final AdvisoryLockRepository advisoryLockRepository;
    private final AuditEventPort auditEventPort;
    private final ServiceService serviceService;

    public TeamService(TeamRepository teamRepository, TeamLifecycleEventProducer eventProducer,
                        PlanLimitService planLimitService, AdvisoryLockRepository advisoryLockRepository,
                        AuditEventPort auditEventPort, ServiceService serviceService) {
        this.teamRepository = teamRepository;
        this.eventProducer = eventProducer;
        this.planLimitService = planLimitService;
        this.advisoryLockRepository = advisoryLockRepository;
        this.auditEventPort = auditEventPort;
        this.serviceService = serviceService;
    }

    @PreAuthorize(Roles.CAN_WRITE)
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

    /**
     * Saves the whole team in one transaction: the name, exactly which services it owns and exactly which users
     * are its members. Runs a fixed number of queries, whatever the number of changes.
     */
    @PreAuthorize(Roles.CAN_WRITE)
    @Transactional
    public Team update(UUID tenantId, Actor actor, UUID teamId, String name,
                       Set<UUID> serviceIds, Set<UUID> memberIds) {
        requireAdminOrTeamMember(tenantId, teamId, actor);
        Team team = teamRepository.findById(tenantId, teamId)
                .orElseThrow(() -> new TeamNotFoundException(teamId));
        if (!team.name().equals(name)) {
            team = rename(team, actor, name);
        }
        serviceService.setTeamServices(tenantId, actor, teamId, serviceIds);
        setMembers(team, actor, memberIds);
        return team;
    }

    private Team rename(Team existing, Actor actor, String name) {
        if (!existing.name().equalsIgnoreCase(name)
                && teamRepository.existsByName(existing.tenantId(), name, existing.id())) {
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

    private void setMembers(Team team, Actor actor, Set<UUID> memberIds) {
        Set<UUID> current = teamRepository.findMembers(team.tenantId(), team.id()).stream()
                .map(TeamMember::userId)
                .collect(Collectors.toSet());
        List<UUID> added = memberIds.stream().filter(id -> !current.contains(id)).toList();
        List<UUID> removed = current.stream().filter(id -> !memberIds.contains(id)).toList();
        teamRepository.addMembers(team.tenantId(), team.id(), added, Instant.now());
        teamRepository.removeMembers(team.tenantId(), team.id(), removed);
        auditEventPort.recordAll(Stream.concat(
                added.stream().map(userId -> auditEvent(team, "member_added", actor, userId)),
                removed.stream().map(userId -> auditEvent(team, "member_removed", actor, userId))).toList());
    }

    private static AuditEvent auditEvent(Team team, String action, Actor actor, UUID userId) {
        return AuditEvent.of(team.tenantId(), AuditKind.TEAM, team.id(), action, actor,
                Map.of("name", team.name(), "userId", userId));
    }

    @PreAuthorize(Roles.CAN_WRITE)
    @Transactional
    public void delete(UUID tenantId, Actor actor, UUID teamId) {
        requireAdminOrTeamMember(tenantId, teamId, actor);
        Team existing = teamRepository.findById(tenantId, teamId)
                .orElseThrow(() -> new TeamNotFoundException(teamId));
        Instant deletedAt = Instant.now();
        teamRepository.softDelete(tenantId, teamId);
        audit(existing, "deleted", actor, Map.of("name", existing.name()));
        eventProducer.publishDeleted(new Team(
                existing.id(), existing.tenantId(), existing.name(),
                existing.createdAt(), existing.updatedAt(), deletedAt));
    }

    @PreAuthorize(Roles.CAN_WRITE)
    @Transactional
    public TeamMember addMember(UUID tenantId, Actor actor, UUID teamId, UUID userId) {
        requireAdminOrTeamMember(tenantId, teamId, actor);
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

    @PreAuthorize(Roles.CAN_WRITE)
    @Transactional
    public void removeMember(UUID tenantId, Actor actor, UUID teamId, UUID userId) {
        requireAdminOrTeamMember(tenantId, teamId, actor);
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
        auditEventPort.record(AuditEvent.of(team.tenantId(), AuditKind.TEAM, team.id(), action, actor, payload));
    }

    private void requireAdminOrTeamMember(UUID tenantId, UUID teamId, Actor actor) {
        if (actor.admin()) {
            return;
        }
        if (actor.userId() == null || !teamRepository.isMember(tenantId, teamId, actor.userId())) {
            throw new AccessDeniedException(
                    "Only ADMIN or a member of this team may perform this action");
        }
    }

}
