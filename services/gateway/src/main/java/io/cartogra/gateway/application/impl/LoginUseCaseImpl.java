package io.cartogra.gateway.application.impl;

import io.cartogra.gateway.api.dto.TokenResponse;
import io.cartogra.gateway.application.LoginUseCase;
import io.cartogra.gateway.config.JwtConfig;
import io.cartogra.gateway.domain.RefreshToken;
import io.cartogra.gateway.domain.User;
import io.cartogra.gateway.domain.exception.UnauthorizedException;
import io.cartogra.gateway.domain.exception.UnverifiedEmailException;
import io.cartogra.gateway.infrastructure.jdbc.RefreshTokenRepository;
import io.cartogra.gateway.infrastructure.jdbc.UserRepository;
import io.cartogra.gateway.infrastructure.jwt.JwtClaims;
import io.cartogra.gateway.infrastructure.jwt.JwtTokenProvider;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class LoginUseCaseImpl implements LoginUseCase {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final JwtConfig jwtConfig;
    private final BCryptPasswordEncoder passwordEncoder;

    public LoginUseCaseImpl(UserRepository userRepository,
                            RefreshTokenRepository refreshTokenRepository,
                            JwtTokenProvider jwtTokenProvider,
                            JwtConfig jwtConfig) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtTokenProvider = jwtTokenProvider;
        this.jwtConfig = jwtConfig;
        this.passwordEncoder = new BCryptPasswordEncoder();
    }

    @Override
    public TokenResponse execute(UUID tenantId, String email, String password) {
        User user = userRepository.findByTenantAndEmail(tenantId, email)
            .orElseThrow(() -> new UnauthorizedException("Invalid credentials"));

        if (user.deletedAt() != null) {
            throw new UnauthorizedException("Invalid credentials");
        }

        if (user.passwordHash() == null || !passwordEncoder.matches(password, user.passwordHash())) {
            throw new UnauthorizedException("Invalid credentials");
        }

        if (!user.emailVerified()) {
            throw new UnverifiedEmailException();
        }

        JwtClaims claims = new JwtClaims(user.id(), user.tenantId(), user.email(),
            user.roles(), Instant.now().plusSeconds(jwtConfig.accessTokenExpirySeconds()));
        String accessToken = jwtTokenProvider.issueAccessToken(claims);
        String rawRefresh = jwtTokenProvider.issueRefreshToken();
        String refreshHash = sha256(rawRefresh);

        RefreshToken token = new RefreshToken(null, user.tenantId(), user.id(), refreshHash,
            null, Instant.now().plusSeconds(jwtConfig.refreshTokenExpirySeconds()), null, null, null);
        refreshTokenRepository.save(token);

        return new TokenResponse(accessToken, rawRefresh, jwtConfig.accessTokenExpirySeconds());
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
