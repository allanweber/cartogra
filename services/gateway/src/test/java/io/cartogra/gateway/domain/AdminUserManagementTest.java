package io.cartogra.gateway.domain;

import io.cartogra.gateway.config.JwtConfig;
import io.cartogra.gateway.infrastructure.email.EmailSender;
import io.cartogra.gateway.infrastructure.jwt.JwtTokenProvider;
import io.cartogra.gateway.repository.InvitationRepository;
import io.cartogra.gateway.repository.RefreshTokenRepository;
import io.cartogra.gateway.repository.TeamMembershipRepository;
import io.cartogra.gateway.repository.TenantRepository;
import io.cartogra.gateway.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminUserManagementTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private TenantRepository tenantRepository;
    @Mock
    private RefreshTokenRepository refreshTokenRepository;
    @Mock
    private TeamMembershipRepository teamMembershipRepository;
    @Mock
    private InvitationRepository invitationRepository;
    @Mock
    private EmailSender emailSender;
    @Mock
    private JwtTokenProvider jwtTokenProvider;
    @Mock
    private BillingPlanService billingPlanService;

    private AuthService auth;

    private final UUID tenantId = UUID.randomUUID();
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @BeforeEach
    void setUp() {
        JwtConfig config = new JwtConfig("secret", 900L, 2592000L, false);
        auth = new AuthService(userRepository, tenantRepository, refreshTokenRepository,
            teamMembershipRepository, invitationRepository, emailSender, passwordEncoder, jwtTokenProvider, config, billingPlanService);
    }

    private User user(UUID id, List<String> roles) {
        return new User(id, tenantId, "u@acme.com", "U", "local", null, "hash",
            true, roles, null, null, null, null, null, null, null, null);
    }

    @Test
    void updateUserRoleChangesRoleWhenOtherAdminsExist() {
        UUID adminA = UUID.randomUUID();
        UUID adminB = UUID.randomUUID();
        when(userRepository.findById(adminA)).thenReturn(Optional.of(user(adminA, List.of("ADMIN"))));
        when(userRepository.findAllByTenant(tenantId)).thenReturn(List.of(
            user(adminA, List.of("ADMIN")), user(adminB, List.of("ADMIN"))));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        auth.updateUserRole(tenantId, adminA, "MEMBER");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().roles()).containsExactly("MEMBER");
    }

    @Test
    void updateUserRoleRejectsDemotingLastAdmin() {
        UUID onlyAdmin = UUID.randomUUID();
        when(userRepository.findById(onlyAdmin)).thenReturn(Optional.of(user(onlyAdmin, List.of("ADMIN"))));
        when(userRepository.findAllByTenant(tenantId)).thenReturn(List.of(user(onlyAdmin, List.of("ADMIN"))));

        assertThatThrownBy(() -> auth.updateUserRole(tenantId, onlyAdmin, "MEMBER"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("last admin");
    }

    @Test
    void removeUserRejectsSelfRemoval() {
        UUID self = UUID.randomUUID();

        assertThatThrownBy(() -> auth.removeUser(tenantId, self, self))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("own account");
        verifyNoInteractions(userRepository);
    }

    @Test
    void removeUserRejectsRemovingLastAdmin() {
        UUID admin = UUID.randomUUID();
        UUID requester = UUID.randomUUID();
        when(userRepository.findById(admin)).thenReturn(Optional.of(user(admin, List.of("ADMIN"))));
        when(userRepository.findAllByTenant(tenantId)).thenReturn(List.of(user(admin, List.of("ADMIN"))));

        assertThatThrownBy(() -> auth.removeUser(tenantId, requester, admin))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("last admin");
    }

    @Test
    void removeUserSoftDeletesWhenOtherAdminsExist() {
        UUID admin = UUID.randomUUID();
        UUID otherAdmin = UUID.randomUUID();
        UUID requester = UUID.randomUUID();
        when(userRepository.findById(admin)).thenReturn(Optional.of(user(admin, List.of("ADMIN"))));
        when(userRepository.findAllByTenant(tenantId)).thenReturn(List.of(
            user(admin, List.of("ADMIN")), user(otherAdmin, List.of("ADMIN"))));

        auth.removeUser(tenantId, requester, admin);

        verify(userRepository).softDelete(tenantId, admin);
    }

    @Test
    void setUserDisabledRejectsDisablingSelf() {
        UUID self = UUID.randomUUID();

        assertThatThrownBy(() -> auth.setUserDisabled(tenantId, self, self, true))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("own account");
        verifyNoInteractions(userRepository);
    }

    @Test
    void setUserDisabledRejectsDisablingLastAdmin() {
        UUID admin = UUID.randomUUID();
        UUID requester = UUID.randomUUID();
        when(userRepository.findById(admin)).thenReturn(Optional.of(user(admin, List.of("ADMIN"))));
        when(userRepository.findAllByTenant(tenantId)).thenReturn(List.of(user(admin, List.of("ADMIN"))));

        assertThatThrownBy(() -> auth.setUserDisabled(tenantId, requester, admin, true))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("last admin");
    }

    @Test
    void setUserDisabledSetsDisabledAtWhenOtherAdminsExist() {
        UUID admin = UUID.randomUUID();
        UUID otherAdmin = UUID.randomUUID();
        UUID requester = UUID.randomUUID();
        when(userRepository.findById(admin)).thenReturn(Optional.of(user(admin, List.of("ADMIN"))));
        when(userRepository.findAllByTenant(tenantId)).thenReturn(List.of(
            user(admin, List.of("ADMIN")), user(otherAdmin, List.of("ADMIN"))));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        User result = auth.setUserDisabled(tenantId, requester, admin, true);

        assertThat(result.disabledAt()).isNotNull();
    }

    @Test
    void setUserDisabledClearsDisabledAtWhenReenabling() {
        UUID targetId = UUID.randomUUID();
        UUID requester = UUID.randomUUID();
        User disabledUser = new User(targetId, tenantId, "u@acme.com", "U", "local", null, "hash",
            true, List.of("MEMBER"), null, null, null, null, null, null, null, java.time.Instant.now());
        when(userRepository.findById(targetId)).thenReturn(Optional.of(disabledUser));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        User result = auth.setUserDisabled(tenantId, requester, targetId, false);

        assertThat(result.disabledAt()).isNull();
    }
}
