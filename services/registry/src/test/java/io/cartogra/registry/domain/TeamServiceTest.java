package io.cartogra.registry.domain;

import io.cartogra.registry.domain.exception.DuplicateTeamNameException;
import io.cartogra.registry.domain.exception.TeamNotFoundException;
import io.cartogra.registry.infrastructure.kafka.TeamLifecycleEventProducer;
import io.cartogra.registry.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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

    private TeamService teamService;

    @BeforeEach
    void setUp() {
        teamService = new TeamService(teamRepository, eventProducer);
    }

    @Test
    void createPublishesCreatedEvent() {
        UUID tenantId = UUID.randomUUID();
        Team saved = team(tenantId, "platform");
        when(teamRepository.existsByName(tenantId, "platform", null)).thenReturn(false);
        when(teamRepository.save(any())).thenReturn(saved);

        teamService.create(tenantId, "platform");

        var captor = ArgumentCaptor.forClass(Team.class);
        verify(eventProducer).publishCreated(captor.capture());
        assertThat(captor.getValue().name()).isEqualTo("platform");
        assertThat(captor.getValue().tenantId()).isEqualTo(tenantId);
    }

    @Test
    void createDuplicateNameThrowsWithoutPublishing() {
        UUID tenantId = UUID.randomUUID();
        when(teamRepository.existsByName(tenantId, "platform", null)).thenReturn(true);

        assertThatThrownBy(() -> teamService.create(tenantId, "platform"))
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

        teamService.update(tenantId, teamId, "new-name");

        var captor = ArgumentCaptor.forClass(Team.class);
        verify(eventProducer).publishUpdated(captor.capture());
        assertThat(captor.getValue().name()).isEqualTo("new-name");
    }

    @Test
    void updateNonExistentTeamThrowsWithoutPublishing() {
        UUID tenantId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        when(teamRepository.findById(tenantId, teamId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> teamService.update(tenantId, teamId, "x"))
                .isInstanceOf(TeamNotFoundException.class);

        verifyNoInteractions(eventProducer);
    }

    @Test
    void deletePublishesDeletedEventWithDeletedAt() {
        UUID tenantId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        Team existing = team(tenantId, teamId, "platform");
        when(teamRepository.findById(tenantId, teamId)).thenReturn(Optional.of(existing));

        teamService.delete(tenantId, teamId);

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

        assertThatThrownBy(() -> teamService.delete(tenantId, teamId))
                .isInstanceOf(TeamNotFoundException.class);

        verifyNoInteractions(eventProducer);
    }

    private Team team(UUID tenantId, String name) {
        return team(tenantId, UUID.randomUUID(), name);
    }

    private Team team(UUID tenantId, UUID teamId, String name) {
        Instant now = Instant.now();
        return new Team(teamId, tenantId, name, now, now, null);
    }
}
