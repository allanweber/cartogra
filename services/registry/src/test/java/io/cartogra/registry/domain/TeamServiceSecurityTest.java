package io.cartogra.registry.domain;

import io.cartogra.registry.infrastructure.kafka.TeamLifecycleEventProducer;
import io.cartogra.registry.repository.PlanLimitRepository;
import io.cartogra.registry.repository.TeamRepository;
import io.cartogra.test.PostgresTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = "spring.flyway.enabled=false")
class TeamServiceSecurityTest {

    @MockitoBean TeamRepository teamRepository;
    @MockitoBean TeamLifecycleEventProducer eventProducer;
    @MockitoBean PlanLimitRepository planLimitRepository;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        PostgresTestSupport.POSTGRES.start();
        registry.add("spring.datasource.url", PostgresTestSupport.POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", PostgresTestSupport.POSTGRES::getUsername);
        registry.add("spring.datasource.password", PostgresTestSupport.POSTGRES::getPassword);
    }

    @Autowired TeamService teamService;

    private static final UUID TENANT = UUID.randomUUID();

    @Test
    void update_withoutAuthentication_throwsSecurityException() {
        assertThatThrownBy(() -> teamService.update(TENANT, UUID.randomUUID(), "x", null))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void update_asPlainMember_throwsAccessDenied() {
        assertThatThrownBy(() -> teamService.update(TENANT, UUID.randomUUID(), "x", null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(roles = "TEAM_OWNER")
    void update_asTeamOwnerOfThisTeam_succeeds() {
        UUID teamId = UUID.randomUUID();
        UUID requestedBy = UUID.randomUUID();
        Instant now = Instant.now();
        Team existing = new Team(teamId, TENANT, "old-name", now, now, null);
        when(teamRepository.isMember(TENANT, teamId, requestedBy)).thenReturn(true);
        when(teamRepository.findById(TENANT, teamId)).thenReturn(Optional.of(existing));
        when(teamRepository.existsByName(TENANT, "new-name", teamId)).thenReturn(false);
        when(teamRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        teamService.update(TENANT, teamId, "new-name", requestedBy);
    }

    @Test
    @WithMockUser(roles = "TEAM_OWNER")
    void update_asTeamOwnerOfAnotherTeam_throwsAccessDenied() {
        UUID teamId = UUID.randomUUID();
        UUID requestedBy = UUID.randomUUID();
        when(teamRepository.isMember(TENANT, teamId, requestedBy)).thenReturn(false);

        assertThatThrownBy(() -> teamService.update(TENANT, teamId, "new-name", requestedBy))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void delete_asPlainMember_throwsAccessDenied() {
        assertThatThrownBy(() -> teamService.delete(TENANT, UUID.randomUUID(), null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void addMember_asPlainMember_throwsAccessDenied() {
        assertThatThrownBy(() -> teamService.addMember(TENANT, UUID.randomUUID(), UUID.randomUUID(), null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void removeMember_asPlainMember_throwsAccessDenied() {
        assertThatThrownBy(() -> teamService.removeMember(TENANT, UUID.randomUUID(), UUID.randomUUID(), null))
                .isInstanceOf(AccessDeniedException.class);
    }
}
