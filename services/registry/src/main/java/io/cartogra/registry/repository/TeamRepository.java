package io.cartogra.registry.repository;

import io.cartogra.registry.domain.Team;
import io.cartogra.registry.domain.TeamMember;

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

    Optional<Team> findByTenantAndName(UUID tenantId, String name);

    boolean isMember(UUID tenantId, UUID teamId, UUID userId);

    Set<UUID> findTeamIdsByMember(UUID tenantId, UUID userId);

    List<TeamMember> findMembers(UUID tenantId, UUID teamId);

    TeamMember addMember(TeamMember member);

    void removeMember(UUID tenantId, UUID teamId, UUID userId);
}
