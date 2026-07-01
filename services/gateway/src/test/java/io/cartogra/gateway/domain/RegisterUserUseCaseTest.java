package io.cartogra.gateway.domain;

import io.cartogra.gateway.api.dto.RegisterRequest;
import io.cartogra.gateway.api.dto.RegisterResponse;
import io.cartogra.gateway.config.JwtConfig;
import io.cartogra.gateway.infrastructure.email.EmailSender;
import io.cartogra.gateway.repository.RefreshTokenRepository;
import io.cartogra.gateway.repository.TenantRepository;
import io.cartogra.gateway.repository.UserRepository;
import io.cartogra.gateway.infrastructure.jwt.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RegisterUserUseCaseTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private TenantRepository tenantRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private EmailSender emailSender;

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    private AuthService auth;

    private final UUID fixedTenantId = UUID.randomUUID();
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @BeforeEach
    void setUp() {
        JwtConfig config = new JwtConfig("secret", 900L, 2592000L, false);
        auth = new AuthService(userRepository, tenantRepository, refreshTokenRepository,
            emailSender, passwordEncoder, jwtTokenProvider, config);
        when(tenantRepository.save(any(Tenant.class)))
            .thenReturn(new Tenant(fixedTenantId, fixedTenantId, "name", "slug", "free", null, null, null));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void newUserIsRegisteredAndEmailSent() throws InterruptedException {
        RegisterRequest request = new RegisterRequest("user@example.com", "password123", "Acme Corp");

        RegisterResponse response = auth.register(request);

        assertThat(response.tenantId()).isEqualTo(fixedTenantId);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        User saved = userCaptor.getValue();
        assertThat(saved.email()).isEqualTo("user@example.com");
        assertThat(saved.tenantId()).isEqualTo(fixedTenantId);
        assertThat(saved.emailVerified()).isFalse();
        assertThat(saved.emailVerificationToken()).isNotNull();
        assertThat(saved.passwordHash()).isNotNull();
        assertThat(saved.authProvider()).isEqualTo("local");
        assertThat(saved.roles()).containsExactly("ADMIN");

        // Email is sent async — give the virtual thread executor time to fire
        Thread.sleep(100);
        verify(emailSender).sendVerification(eq("user@example.com"), anyString());
    }

    @Test
    void tenantCreatedWithOrgNameWhenProvided() {
        RegisterRequest request = new RegisterRequest("user@example.com", "password123", "Acme Corp");

        auth.register(request);

        ArgumentCaptor<Tenant> tenantCaptor = ArgumentCaptor.forClass(Tenant.class);
        verify(tenantRepository).save(tenantCaptor.capture());
        assertThat(tenantCaptor.getValue().name()).isEqualTo("Acme Corp");
    }

    @Test
    void tenantCreatedWithEmailWhenOrgNameAbsent() {
        RegisterRequest request = new RegisterRequest("user@example.com", "password123", null);

        auth.register(request);

        ArgumentCaptor<Tenant> tenantCaptor = ArgumentCaptor.forClass(Tenant.class);
        verify(tenantRepository).save(tenantCaptor.capture());
        assertThat(tenantCaptor.getValue().name()).isEqualTo("user@example.com");
    }

    @Test
    void passwordIsStoredAsHash() {
        String plainPassword = "password123";
        RegisterRequest request = new RegisterRequest("user@example.com", plainPassword, null);

        auth.register(request);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().passwordHash()).isNotEqualTo(plainPassword);
        assertThat(captor.getValue().passwordHash()).startsWith("$2a$");
    }

    @Test
    void userIsCreatedAsAdmin() {
        RegisterRequest request = new RegisterRequest("user@example.com", "password123", "Org");

        auth.register(request);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().roles()).containsExactly("ADMIN");
    }
}
