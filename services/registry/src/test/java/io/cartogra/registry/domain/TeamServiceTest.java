package io.cartogra.registry.domain;

import io.cartogra.common.audit.AuditEvent;
import io.cartogra.common.identity.Actor;
import io.cartogra.registry.domain.exception.DuplicateTeamNameException;
import io.cartogra.registry.domain.exception.TeamNotFoundException;
import io.cartogra.registry.domain.exception.UserNotFoundException;
import io.cartogra.registry.infrastructure.kafka.TeamLifecycleEventProducer;
import io.cartogra.registry.repository.TeamRepository;
import io.cartogra.web.lock.AdvisoryLockRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TeamServiceTest {

    private static final Actor ADMIN = Actor.admin(UUID.randomUUID());

    @Mock TeamRepository teamRepository;
    @Mock TeamLifecycleEventProducer eventProducer;
    @Mock PlanLimitService planLimitService;
    @Mock AdvisoryLockRepository advisoryLockRepository;
    @Mock ServiceService serviceService;

    private TeamService teamService;
    private final List<AuditEvent> audits = new ArrayList<>();

    @BeforeEach
    void setUp() {
        lenient().when(advisoryLockRepository.tryAcquireLock(anyLong())).thenReturn(true);
        teamService = new TeamService(teamRepository, eventProducer, planLimitService, advisoryLockRepository, audits::add,
                serviceService);
    }

    @Test
    void createPublishesCreatedEvent() {
        UUID tenantId = UUID.randomUUID();
        Team saved = team(tenantId, "platform");
        when(teamRepository.existsByName(tenantId, "platform", null)).thenReturn(false);
        when(planLimitService.getLimits(tenantId))
                .thenReturn(new PlanLimits(PlanLimits.UNLIMITED, PlanLimits.UNLIMITED, PlanLimits.UNLIMITED, PlanLimits.UNLIMITED));
        when(teamRepository.save(any())).thenReturn(saved);

        teamService.create(tenantId, ADMIN, "platform");

        var captor = ArgumentCaptor.forClass(Team.class);
        verify(eventProducer).publishCreated(captor.capture());
        assertThat(captor.getValue().name()).isEqualTo("platform");
        assertThat(captor.getValue().tenantId()).isEqualTo(tenantId);
    }

    @Test
    void createDuplicateNameThrowsWithoutPublishing() {
        UUID tenantId = UUID.randomUUID();
        when(teamRepository.existsByName(tenantId, "platform", null)).thenReturn(true);

        assertThatThrownBy(() -> teamService.create(tenantId, ADMIN, "platform"))
                .isInstanceOf(DuplicateTeamNameException.class);

        verifyNoInteractions(eventProducer);
    }

    @Test
    void updatePublishesUpdatedEvent() {
        UUID tenantId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        Team existing = team(tenantId, teamId, "old-name");
        Team saved = team(tenantId, teamId, "new-name");
        when(teamRepository.findById(tenantId, teamId)).thenReturn(Optional.of(existing));
        when(teamRepository.existsByName(tenantId, "new-name", teamId)).thenReturn(false);
        when(teamRepository.save(any())).thenReturn(saved);

        teamService.update(tenantId, ADMIN, teamId, "new-name", Set.of(), Set.of());

        var captor = ArgumentCaptor.forClass(Team.class);
        verify(eventProducer).publishUpdated(captor.capture());
        assertThat(captor.getValue().name()).isEqualTo("new-name");
    }

    @Test
    void updateNonExistentTeamThrowsWithoutPublishing() {
        UUID tenantId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        when(teamRepository.findById(tenantId, teamId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> teamService.update(tenantId, ADMIN, teamId, "x", Set.of(), Set.of()))
                .isInstanceOf(TeamNotFoundException.class);

        verifyNoInteractions(eventProducer);
    }

    @Test
    void deletePublishesDeletedEventWithDeletedAt() {
        UUID tenantId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        Team existing = team(tenantId, teamId, "platform");
        when(teamRepository.findById(tenantId, teamId)).thenReturn(Optional.of(existing));

        teamService.delete(tenantId, ADMIN, teamId);

        verify(teamRepository).softDelete(tenantId, teamId);
        var captor = ArgumentCaptor.forClass(Team.class);
        verify(eventProducer).publishDeleted(captor.capture());
        assertThat(captor.getValue().deletedAt()).isNotNull();
        assertThat(captor.getValue().name()).isEqualTo("platform");
    }

    @Test
    void deleteNonExistentTeamThrowsWithoutPublishing() {
        UUID tenantId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        when(teamRepository.findById(tenantId, teamId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> teamService.delete(tenantId, ADMIN, teamId))
                .isInstanceOf(TeamNotFoundException.class);

        verifyNoInteractions(eventProducer);
    }

    @Test
    void createBeyondPlanLimitThrows() {
        UUID tenantId = UUID.randomUUID();
        when(teamRepository.existsByName(tenantId, "platform", null)).thenReturn(false);
        when(planLimitService.getLimits(tenantId)).thenReturn(new PlanLimits(-1, -1, -1, 2));
        when(teamRepository.count(tenantId)).thenReturn(2L);

        assertThatThrownBy(() -> teamService.create(tenantId, ADMIN, "platform"))
                .isInstanceOf(io.cartogra.registry.domain.exception.PlanLimitExceededException.class);

        verifyNoInteractions(eventProducer);
    }

    @Test
    void createAcquiresAndReleasesPlanLimitLockAroundCountCheckAndSave() {
        UUID tenantId = UUID.randomUUID();
        Team saved = team(tenantId, "platform");
        when(teamRepository.existsByName(tenantId, "platform", null)).thenReturn(false);
        // Finite limit so the count-check isn't short-circuited by PlanLimits.UNLIMITED.
        when(planLimitService.getLimits(tenantId)).thenReturn(new PlanLimits(-1, -1, -1, 10));
        when(teamRepository.count(tenantId)).thenReturn(0L);
        when(teamRepository.save(any())).thenReturn(saved);

        teamService.create(tenantId, ADMIN, "platform");

        InOrder inOrder = inOrder(advisoryLockRepository, teamRepository);
        inOrder.verify(advisoryLockRepository).tryAcquireLock(anyLong());
        inOrder.verify(teamRepository).count(tenantId);
        inOrder.verify(teamRepository).save(any());
        inOrder.verify(advisoryLockRepository).releaseLock(anyLong());
    }

    @Test
    void createReleasesLockWhenPlanLimitExceeded() {
        UUID tenantId = UUID.randomUUID();
        when(teamRepository.existsByName(tenantId, "platform", null)).thenReturn(false);
        when(planLimitService.getLimits(tenantId)).thenReturn(new PlanLimits(-1, -1, -1, 2));
        when(teamRepository.count(tenantId)).thenReturn(2L);

        assertThatThrownBy(() -> teamService.create(tenantId, ADMIN, "platform"))
                .isInstanceOf(io.cartogra.registry.domain.exception.PlanLimitExceededException.class);

        verify(advisoryLockRepository).tryAcquireLock(anyLong());
        verify(advisoryLockRepository).releaseLock(anyLong());
        verify(teamRepository, never()).save(any());
    }

    @Test
    void createThrowsWhenPlanLimitLockCannotBeAcquired() {
        UUID tenantId = UUID.randomUUID();
        when(teamRepository.existsByName(tenantId, "platform", null)).thenReturn(false);
        when(advisoryLockRepository.tryAcquireLock(anyLong())).thenReturn(false);

        assertThatThrownBy(() -> teamService.create(tenantId, ADMIN, "platform"))
                .isInstanceOf(IllegalStateException.class);

        verify(advisoryLockRepository, never()).releaseLock(anyLong());
        verify(teamRepository, never()).save(any());
    }

    @Test
    void createByTeamOwnerAutoAddsCreatorAsMember() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Team saved = team(tenantId, "platform");
        when(teamRepository.existsByName(tenantId, "platform", null)).thenReturn(false);
        when(planLimitService.getLimits(tenantId))
                .thenReturn(new PlanLimits(PlanLimits.UNLIMITED, PlanLimits.UNLIMITED, PlanLimits.UNLIMITED, PlanLimits.UNLIMITED));
        when(teamRepository.save(any())).thenReturn(saved);

        teamService.create(tenantId, Actor.user(userId), "platform");

        var captor = ArgumentCaptor.forClass(TeamMember.class);
        verify(teamRepository).addMember(captor.capture());
        assertThat(captor.getValue().userId()).isEqualTo(userId);
        assertThat(captor.getValue().teamId()).isEqualTo(saved.id());
    }

    @Test
    void createByAdminDoesNotAutoAddCreatorAsMember() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Team saved = team(tenantId, "platform");
        when(teamRepository.existsByName(tenantId, "platform", null)).thenReturn(false);
        when(planLimitService.getLimits(tenantId))
                .thenReturn(new PlanLimits(PlanLimits.UNLIMITED, PlanLimits.UNLIMITED, PlanLimits.UNLIMITED, PlanLimits.UNLIMITED));
        when(teamRepository.save(any())).thenReturn(saved);

        teamService.create(tenantId, Actor.admin(userId), "platform");

        verify(teamRepository, never()).addMember(any());
    }

    @Test
    void updateByTeamOwnerSucceeds() {
        UUID tenantId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Team existing = team(tenantId, teamId, "old-name");
        Team saved = team(tenantId, teamId, "new-name");
        when(teamRepository.isMember(tenantId, teamId, userId)).thenReturn(true);
        when(teamRepository.findById(tenantId, teamId)).thenReturn(Optional.of(existing));
        when(teamRepository.existsByName(tenantId, "new-name", teamId)).thenReturn(false);
        when(teamRepository.save(any())).thenReturn(saved);

        teamService.update(tenantId, Actor.user(userId), teamId, "new-name", Set.of(), Set.of());

        verify(eventProducer).publishUpdated(any());
    }

    @Test
    void updateSavesServicesAndMemberDifferencesInBulk() {
        UUID tenantId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        UUID kept = UUID.randomUUID();
        UUID dropped = UUID.randomUUID();
        UUID added = UUID.randomUUID();
        Set<UUID> serviceIds = Set.of(UUID.randomUUID());
        when(teamRepository.findById(tenantId, teamId)).thenReturn(Optional.of(team(tenantId, teamId, "same")));
        when(teamRepository.findMembers(tenantId, teamId)).thenReturn(List.of(member(tenantId, teamId, kept),
                member(tenantId, teamId, dropped)));
        when(teamRepository.findTenantUserIds(tenantId, List.of(added))).thenReturn(Set.of(added));

        teamService.update(tenantId, ADMIN, teamId, "same", serviceIds, Set.of(kept, added));

        verify(serviceService).setTeamServices(tenantId, ADMIN, teamId, serviceIds);
        verify(teamRepository).addMembers(eq(tenantId), eq(teamId), eq(List.of(added)), any());
        verify(teamRepository).removeMembers(tenantId, teamId, List.of(dropped));
        verify(teamRepository, never()).addMember(any());
        verify(teamRepository, never()).removeMember(any(), any(), any());
        assertThat(audits).extracting(AuditEvent::action, e -> e.payload().get("userId"))
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("member_added", added),
                        org.assertj.core.groups.Tuple.tuple("member_removed", dropped));
    }

    @Test
    void updateWithSameNameDoesNotRenameButStillSavesTheLists() {
        UUID tenantId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        when(teamRepository.findById(tenantId, teamId)).thenReturn(Optional.of(team(tenantId, teamId, "same")));

        Team result = teamService.update(tenantId, ADMIN, teamId, "same", Set.of(), Set.of());

        assertThat(result.name()).isEqualTo("same");
        verify(teamRepository, never()).save(any());
        verifyNoInteractions(eventProducer);
        verify(serviceService).setTeamServices(tenantId, ADMIN, teamId, Set.of());
        verify(teamRepository).findMembers(tenantId, teamId);
        assertThat(audits).isEmpty();
    }

    @Test
    void updateWithUnchangedMembersRecordsNoMemberAudit() {
        UUID tenantId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        UUID kept = UUID.randomUUID();
        when(teamRepository.findById(tenantId, teamId)).thenReturn(Optional.of(team(tenantId, teamId, "same")));
        when(teamRepository.findMembers(tenantId, teamId)).thenReturn(List.of(member(tenantId, teamId, kept)));

        teamService.update(tenantId, ADMIN, teamId, "same", Set.of(), Set.of(kept));

        verify(teamRepository).addMembers(eq(tenantId), eq(teamId), eq(List.of()), any());
        verify(teamRepository).removeMembers(tenantId, teamId, List.of());
        assertThat(audits).isEmpty();
    }

    @Test
    void updateByNonMemberIsDeniedBeforeAnythingIsSaved() {
        UUID tenantId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(teamRepository.isMember(tenantId, teamId, userId)).thenReturn(false);

        assertThatThrownBy(() -> teamService.update(tenantId, Actor.user(userId), teamId, "x", Set.of(), Set.of()))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);

        verifyNoInteractions(serviceService, eventProducer);
        verify(teamRepository, never()).addMembers(any(), any(), anyCollection(), any());
        verify(teamRepository, never()).removeMembers(any(), any(), anyCollection());
    }

    @Test
    void updateRejectsMemberFromAnotherTenantBeforeAnythingIsSaved() {
        UUID tenantId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        UUID foreignUser = UUID.randomUUID();
        when(teamRepository.findById(tenantId, teamId)).thenReturn(Optional.of(team(tenantId, teamId, "same")));
        when(teamRepository.findTenantUserIds(tenantId, List.of(foreignUser))).thenReturn(Set.of());

        assertThatThrownBy(() -> teamService.update(tenantId, ADMIN, teamId, "same", Set.of(), Set.of(foreignUser)))
                .isInstanceOf(UserNotFoundException.class);

        verify(teamRepository, never()).addMembers(any(), any(), anyCollection(), any());
        verify(teamRepository, never()).removeMembers(any(), any(), anyCollection());
        assertThat(audits).isEmpty();
    }

    @Test
    void addMemberRejectsUserFromAnotherTenant() {
        UUID tenantId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        UUID foreignUser = UUID.randomUUID();
        when(teamRepository.findById(tenantId, teamId)).thenReturn(Optional.of(team(tenantId, teamId, "t")));
        when(teamRepository.findTenantUserIds(tenantId, List.of(foreignUser))).thenReturn(Set.of());

        assertThatThrownBy(() -> teamService.addMember(tenantId, ADMIN, teamId, foreignUser))
                .isInstanceOf(UserNotFoundException.class);

        verify(teamRepository, never()).addMember(any());
        assertThat(audits).isEmpty();
    }

    @Test
    void addMemberAddsUserOfTheTenant() {
        UUID tenantId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        TeamMember saved = member(tenantId, teamId, userId);
        when(teamRepository.findById(tenantId, teamId)).thenReturn(Optional.of(team(tenantId, teamId, "t")));
        when(teamRepository.findTenantUserIds(tenantId, List.of(userId))).thenReturn(Set.of(userId));
        when(teamRepository.addMember(any())).thenReturn(saved);

        assertThat(teamService.addMember(tenantId, ADMIN, teamId, userId)).isEqualTo(saved);
        assertThat(audits).extracting(AuditEvent::action).containsExactly("member_added");
    }

    private TeamMember member(UUID tenantId, UUID teamId, UUID userId) {
        return new TeamMember(UUID.randomUUID(), tenantId, teamId, userId, Instant.now());
    }

    private Team team(UUID tenantId, String name) {
        return team(tenantId, UUID.randomUUID(), name);
    }

    private Team team(UUID tenantId, UUID teamId, String name) {
        Instant now = Instant.now();
        return new Team(teamId, tenantId, name, now, now, null);
    }
}
