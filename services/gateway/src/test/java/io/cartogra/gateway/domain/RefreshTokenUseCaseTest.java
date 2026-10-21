package io.cartogra.gateway.domain;

import io.cartogra.gateway.api.dto.TokenResponse;
import io.cartogra.gateway.config.JwtConfig;
import io.cartogra.gateway.domain.exception.UnauthorizedException;
import io.cartogra.gateway.infrastructure.email.EmailSender;
import io.cartogra.gateway.repository.InvitationRepository;
import io.cartogra.gateway.repository.RefreshTokenRepository;
import io.cartogra.gateway.repository.TeamMembershipRepository;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RefreshTokenUseCaseTest {

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private TenantRepository tenantRepository;

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

    private final UUID userId = UUID.randomUUID();
    private final UUID tenantId = UUID.randomUUID();
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    @BeforeEach
    void setUp() {
        JwtConfig config = new JwtConfig("secret", 900L, 2592000L, false);
        auth = new AuthService(userRepository, tenantRepository, refreshTokenRepository,
            teamMembershipRepository, invitationRepository, emailSender, encoder, jwtTokenProvider, config, billingPlanService);
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private User activeUser() {
        return new User(userId, tenantId, "user@test.com", null, "local", null,
            "hash", true, List.of("VIEWER"), null, null,
            null, null, Instant.now(), Instant.now(), null, null);
    }

    private RefreshToken session(String tokenHash, String previousHash, Instant lastUsedAt) {
        return new RefreshToken(UUID.randomUUID(), tenantId, userId, tokenHash, previousHash,
            Instant.now(), Instant.now().plusSeconds(2592000), lastUsedAt, null, Instant.now(), null);
    }

    @Test
    void validRefreshTokenRotatesSessionInPlace() {
        String rawToken = "raw-refresh-token";
        String tokenHash = sha256(rawToken);
        RefreshToken stored = session(tokenHash, null, null);

        when(refreshTokenRepository.findActiveByHash(tokenHash)).thenReturn(Optional.of(stored));
        when(userRepository.findById(userId)).thenReturn(Optional.of(activeUser()));
        when(jwtTokenProvider.issueAccessToken(any(JwtClaims.class))).thenReturn("new-access-token");
        when(jwtTokenProvider.issueRefreshToken()).thenReturn("new-raw-refresh");
        when(refreshTokenRepository.rotate(eq(tokenHash), eq(sha256("new-raw-refresh")), any(Instant.class)))
            .thenReturn(Optional.of(stored));

        TokenResponse response = auth.refreshToken(rawToken);

        assertThat(response.accessToken()).isEqualTo("new-access-token");
        assertThat(response.refreshToken()).isEqualTo("new-raw-refresh");
        verify(refreshTokenRepository, never()).save(any());
        verify(refreshTokenRepository, never()).revoke(any());
    }

    @Test
    void unknownTokenThrowsUnauthorizedWithoutRevoking() {
        String rawToken = "expired-token";
        String tokenHash = sha256(rawToken);

        when(refreshTokenRepository.findActiveByHash(tokenHash)).thenReturn(Optional.empty());
        when(refreshTokenRepository.findActiveByPreviousHash(tokenHash)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> auth.refreshToken(rawToken))
            .isInstanceOf(UnauthorizedException.class);

        verify(userRepository, never()).findById(any());
        verify(jwtTokenProvider, never()).issueAccessToken(any());
        verify(refreshTokenRepository, never()).revoke(any());
    }

    @Test
    void replayedTokenAfterGraceWindowRevokesSession() {
        String rawToken = "rotated-token";
        String tokenHash = sha256(rawToken);
        RefreshToken rotated = session("current-hash", tokenHash,
            Instant.now().minus(AuthService.REFRESH_REPLAY_GRACE).minusSeconds(1));

        when(refreshTokenRepository.findActiveByHash(tokenHash)).thenReturn(Optional.empty());
        when(refreshTokenRepository.findActiveByPreviousHash(tokenHash)).thenReturn(Optional.of(rotated));

        assertThatThrownBy(() -> auth.refreshToken(rawToken))
            .isInstanceOf(UnauthorizedException.class);

        verify(refreshTokenRepository).revoke(rotated.id());
    }

    @Test
    void replayedTokenInsideGraceWindowIsRejectedWithoutRevoking() {
        String rawToken = "racing-token";
        String tokenHash = sha256(rawToken);
        RefreshToken rotated = session("current-hash", tokenHash, Instant.now().minusSeconds(1));

        when(refreshTokenRepository.findActiveByHash(tokenHash)).thenReturn(Optional.empty());
        when(refreshTokenRepository.findActiveByPreviousHash(tokenHash)).thenReturn(Optional.of(rotated));

        assertThatThrownBy(() -> auth.refreshToken(rawToken))
            .isInstanceOf(UnauthorizedException.class);

        verify(refreshTokenRepository, never()).revoke(any());
    }

    @Test
    void losingConcurrentRotationIsRejected() {
        String rawToken = "contended-token";
        String tokenHash = sha256(rawToken);
        RefreshToken stored = session(tokenHash, null, null);
        RefreshToken rotatedByWinner = session("winner-hash", tokenHash, Instant.now());

        when(refreshTokenRepository.findActiveByHash(tokenHash)).thenReturn(Optional.of(stored));
        when(userRepository.findById(userId)).thenReturn(Optional.of(activeUser()));
        when(jwtTokenProvider.issueRefreshToken()).thenReturn("loser-refresh");
        when(refreshTokenRepository.rotate(eq(tokenHash), any(), any())).thenReturn(Optional.empty());
        when(refreshTokenRepository.findActiveByPreviousHash(tokenHash)).thenReturn(Optional.of(rotatedByWinner));

        assertThatThrownBy(() -> auth.refreshToken(rawToken))
            .isInstanceOf(UnauthorizedException.class);

        verify(jwtTokenProvider, never()).issueAccessToken(any());
        verify(refreshTokenRepository, never()).revoke(any());
    }

    @Test
    void disabledUserRevokesSessionWithoutRotating() {
        String rawToken = "disabled-token";
        String tokenHash = sha256(rawToken);
        RefreshToken stored = session(tokenHash, null, null);
        User active = activeUser();
        User disabled = new User(userId, tenantId, "user@test.com", null, "local", null,
            "hash", true, List.of("VIEWER"), null, null,
            null, null, active.createdAt(), active.updatedAt(), null, Instant.now());

        when(refreshTokenRepository.findActiveByHash(tokenHash)).thenReturn(Optional.of(stored));
        when(userRepository.findById(userId)).thenReturn(Optional.of(disabled));

        assertThatThrownBy(() -> auth.refreshToken(rawToken))
            .isInstanceOf(UnauthorizedException.class);

        verify(refreshTokenRepository).revoke(stored.id());
        verify(refreshTokenRepository, never()).rotate(any(), any(), any());
    }
}
