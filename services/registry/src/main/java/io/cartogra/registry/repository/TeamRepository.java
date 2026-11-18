package io.cartogra.registry.repository;

import io.cartogra.registry.domain.Team;
import io.cartogra.registry.domain.TeamMember;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface TeamRepository {

    Optional<Team> findById(UUID tenantId, UUID id);

    List<Team> findAll(UUID tenantId, int limit, int offset);

    long count(UUID tenantId);

    Team save(Team team);

    void softDelete(UUID tenantId, UUID id);

    boolean existsByName(UUID tenantId, String name, UUID excludeId);

    List<Team> findByTenantAndSlug(UUID tenantId, String slug);

    boolean isMember(UUID tenantId, UUID teamId, UUID userId);

    /** Returns the IDs from {@code userIds} that belong to a live user of the tenant. */
    Set<UUID> findTenantUserIds(UUID tenantId, Collection<UUID> userIds);

    Set<UUID> findTeamIdsByMember(UUID tenantId, UUID userId);

    List<TeamMember> findMembers(UUID tenantId, UUID teamId);

    TeamMember addMember(TeamMember member);

    void removeMember(UUID tenantId, UUID teamId, UUID userId);

    /** Adds every member in one statement; a user already in the team is skipped. */
    void addMembers(UUID tenantId, UUID teamId, Collection<UUID> userIds, Instant at);

    /** Removes every listed user from the team in one statement. */
    void removeMembers(UUID tenantId, UUID teamId, Collection<UUID> userIds);
}
