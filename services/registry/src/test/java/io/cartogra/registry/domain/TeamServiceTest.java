package io.cartogra.registry.domain;

import io.cartogra.registry.domain.exception.DuplicateTeamNameException;
import io.cartogra.registry.domain.exception.TeamNotFoundException;
import io.cartogra.registry.infrastructure.kafka.TeamLifecycleEventProducer;
import io.cartogra.registry.repository.TeamRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TeamServiceTest {

    @Mock TeamRepository teamRepository;
    @Mock TeamLifecycleEventProducer eventProducer;
    @Mock PlanLimitService planLimitService;

    private TeamService teamService;

    @BeforeEach
    void setUp() {
        teamService = new TeamService(teamRepository, eventProducer, planLimitService);
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("admin", null, new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createPublishesCreatedEvent() {
        UUID tenantId = UUID.randomUUID();
        Team saved = team(tenantId, "platform");
        when(teamRepository.existsByName(tenantId, "platform", null)).thenReturn(false);
        when(planLimitService.getLimits(tenantId))
                .thenReturn(new PlanLimits(PlanLimits.UNLIMITED, PlanLimits.UNLIMITED, PlanLimits.UNLIMITED, PlanLimits.UNLIMITED));
        when(teamRepository.save(any())).thenReturn(saved);

        teamService.create(tenantId, "platform", null);

        var captor = ArgumentCaptor.forClass(Team.class);
        verify(eventProducer).publishCreated(captor.capture());
        assertThat(captor.getValue().name()).isEqualTo("platform");
        assertThat(captor.getValue().tenantId()).isEqualTo(tenantId);
    }

    @Test
    void createDuplicateNameThrowsWithoutPublishing() {
        UUID tenantId = UUID.randomUUID();
        when(teamRepository.existsByName(tenantId, "platform", null)).thenReturn(true);

        assertThatThrownBy(() -> teamService.create(tenantId, "platform", null))
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

        teamService.update(tenantId, teamId, "new-name", null);

        var captor = ArgumentCaptor.forClass(Team.class);
        verify(eventProducer).publishUpdated(captor.capture());
        assertThat(captor.getValue().name()).isEqualTo("new-name");
    }

    @Test
    void updateNonExistentTeamThrowsWithoutPublishing() {
        UUID tenantId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        when(teamRepository.findById(tenantId, teamId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> teamService.update(tenantId, teamId, "x", null))
                .isInstanceOf(TeamNotFoundException.class);

        verifyNoInteractions(eventProducer);
    }

    @Test
    void deletePublishesDeletedEventWithDeletedAt() {
        UUID tenantId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        Team existing = team(tenantId, teamId, "platform");
        when(teamRepository.findById(tenantId, teamId)).thenReturn(Optional.of(existing));

        teamService.delete(tenantId, teamId, null);

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

        assertThatThrownBy(() -> teamService.delete(tenantId, teamId, null))
                .isInstanceOf(TeamNotFoundException.class);

        verifyNoInteractions(eventProducer);
    }

    @Test
    void createBeyondPlanLimitThrows() {
        UUID tenantId = UUID.randomUUID();
        when(teamRepository.existsByName(tenantId, "platform", null)).thenReturn(false);
        when(planLimitService.getLimits(tenantId)).thenReturn(new PlanLimits(-1, -1, -1, 2));
        when(teamRepository.count(tenantId)).thenReturn(2L);

        assertThatThrownBy(() -> teamService.create(tenantId, "platform", null))
                .isInstanceOf(io.cartogra.registry.domain.exception.PlanLimitExceededException.class);

        verifyNoInteractions(eventProducer);
    }

    @Test
    void createByTeamOwnerAutoAddsCreatorAsMember() {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("owner", null, new SimpleGrantedAuthority("ROLE_TEAM_OWNER")));
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Team saved = team(tenantId, "platform");
        when(teamRepository.existsByName(tenantId, "platform", null)).thenReturn(false);
        when(planLimitService.getLimits(tenantId))
                .thenReturn(new PlanLimits(PlanLimits.UNLIMITED, PlanLimits.UNLIMITED, PlanLimits.UNLIMITED, PlanLimits.UNLIMITED));
        when(teamRepository.save(any())).thenReturn(saved);

        teamService.create(tenantId, "platform", userId);

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

        teamService.create(tenantId, "platform", userId);

        verify(teamRepository, never()).addMember(any());
    }

    @Test
    void updateByNonAdminNonMemberIsDenied() {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("user", null, new SimpleGrantedAuthority("ROLE_VIEWER")));
        UUID tenantId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(teamRepository.isMember(tenantId, teamId, userId)).thenReturn(false);

        assertThatThrownBy(() -> teamService.update(tenantId, teamId, "new-name", userId))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);

        verifyNoInteractions(eventProducer);
    }

    @Test
    void updateByNonAdminTeamMemberSucceeds() {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("user", null, new SimpleGrantedAuthority("ROLE_VIEWER")));
        UUID tenantId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Team existing = team(tenantId, teamId, "old-name");
        Team saved = team(tenantId, teamId, "new-name");
        when(teamRepository.isMember(tenantId, teamId, userId)).thenReturn(true);
        when(teamRepository.findById(tenantId, teamId)).thenReturn(Optional.of(existing));
        when(teamRepository.existsByName(tenantId, "new-name", teamId)).thenReturn(false);
        when(teamRepository.save(any())).thenReturn(saved);

        teamService.update(tenantId, teamId, "new-name", userId);

        verify(eventProducer).publishUpdated(any());
    }

    private Team team(UUID tenantId, String name) {
        return team(tenantId, UUID.randomUUID(), name);
    }

    private Team team(UUID tenantId, UUID teamId, String name) {
        Instant now = Instant.now();
        return new Team(teamId, tenantId, name, now, now, null);
    }
}
