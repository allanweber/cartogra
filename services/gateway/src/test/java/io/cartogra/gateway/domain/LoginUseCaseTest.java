package io.cartogra.gateway.domain;

import io.cartogra.gateway.api.dto.TokenResponse;
import io.cartogra.gateway.config.JwtConfig;
import io.cartogra.gateway.domain.exception.UnauthorizedException;
import io.cartogra.gateway.domain.exception.UnverifiedEmailException;
import io.cartogra.gateway.infrastructure.email.EmailSender;
import io.cartogra.gateway.repository.RefreshTokenRepository;
import io.cartogra.gateway.repository.TenantRepository;
import io.cartogra.gateway.repository.UserRepository;
import io.cartogra.gateway.infrastructure.jwt.JwtClaims;
import io.cartogra.gateway.infrastructure.jwt.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LoginUseCaseTest {

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

    private final UUID userId = UUID.randomUUID();
    private final UUID tenantId = UUID.randomUUID();
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    @BeforeEach
    void setUp() {
        JwtConfig config = new JwtConfig("secret", 900L, 2592000L, false);
        auth = new AuthService(userRepository, tenantRepository, refreshTokenRepository,
            emailSender, encoder, jwtTokenProvider, config);
    }

    private User verifiedUser(String password) {
        return new User(userId, tenantId, "user@test.com", null, "local", null,
            encoder.encode(password), true, List.of("VIEWER"), null, null,
            null, null, Instant.now(), Instant.now(), null);
    }

    @Test
    void validCredentialsReturnTokens() {
        when(userRepository.findByEmail("user@test.com"))
            .thenReturn(Optional.of(verifiedUser("password123")));
        when(jwtTokenProvider.issueAccessToken(any(JwtClaims.class))).thenReturn("access-token");
        when(jwtTokenProvider.issueRefreshToken()).thenReturn("raw-refresh-token-64chars-padded-here-0000000000000000000000");
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(inv -> inv.getArgument(0));

        TokenResponse response = auth.login("user@test.com", "password123");

        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.refreshToken()).isNotNull();
        assertThat(response.expiresIn()).isEqualTo(900L);
        verify(refreshTokenRepository).save(any(RefreshToken.class));
    }

    @Test
    void wrongPasswordThrowsUnauthorized() {
        when(userRepository.findByEmail("user@test.com"))
            .thenReturn(Optional.of(verifiedUser("correct-password")));

        assertThatThrownBy(() -> auth.login("user@test.com", "wrong-password"))
            .isInstanceOf(UnauthorizedException.class);

        verify(jwtTokenProvider, never()).issueAccessToken(any());
    }

    @Test
    void unknownEmailThrowsUnauthorized() {
        when(userRepository.findByEmail("unknown@test.com"))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> auth.login("unknown@test.com", "password"))
            .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void unverifiedUserThrowsUnverifiedEmailException() {
        User unverified = new User(userId, tenantId, "user@test.com", null, "local", null,
            encoder.encode("password123"), false, List.of("VIEWER"), "123456",
            Instant.now().plusSeconds(900), null, null, Instant.now(), Instant.now(), null);
        when(userRepository.findByEmail("user@test.com"))
            .thenReturn(Optional.of(unverified));

        assertThatThrownBy(() -> auth.login("user@test.com", "password123"))
            .isInstanceOf(UnverifiedEmailException.class);
    }

    @Test
    void softDeletedUserThrowsUnauthorized() {
        User deleted = new User(userId, tenantId, "user@test.com", null, "local", null,
            encoder.encode("password123"), true, List.of("VIEWER"), null, null,
            null, null, Instant.now(), Instant.now(), Instant.now());
        when(userRepository.findByEmail("user@test.com"))
            .thenReturn(Optional.of(deleted));

        assertThatThrownBy(() -> auth.login("user@test.com", "password123"))
            .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void errorMessageIsIdenticalForWrongPasswordAndUnknownUser() {
        when(userRepository.findByEmail("unknown@test.com"))
            .thenReturn(Optional.empty());
        when(userRepository.findByEmail("user@test.com"))
            .thenReturn(Optional.of(verifiedUser("correct")));

        String notFoundMessage = null;
        String wrongPasswordMessage = null;

        try {
            auth.login("unknown@test.com", "any");
        } catch (UnauthorizedException e) {
            notFoundMessage = e.getMessage();
        }

        try {
            auth.login("user@test.com", "wrong");
        } catch (UnauthorizedException e) {
            wrongPasswordMessage = e.getMessage();
        }

        assertThat(notFoundMessage).isEqualTo(wrongPasswordMessage);
    }
}
