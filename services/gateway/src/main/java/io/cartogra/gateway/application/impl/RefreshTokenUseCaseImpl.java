package io.cartogra.gateway.application.impl;

import io.cartogra.gateway.api.dto.TokenResponse;
import io.cartogra.gateway.application.RefreshTokenUseCase;
import io.cartogra.gateway.config.JwtConfig;
import io.cartogra.gateway.domain.RefreshToken;
import io.cartogra.gateway.domain.User;
import io.cartogra.gateway.domain.exception.UnauthorizedException;
import io.cartogra.gateway.infrastructure.jdbc.RefreshTokenRepository;
import io.cartogra.gateway.infrastructure.jdbc.UserRepository;
import io.cartogra.gateway.infrastructure.jwt.JwtClaims;
import io.cartogra.gateway.infrastructure.jwt.JwtTokenProvider;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

@Service
public class RefreshTokenUseCaseImpl implements RefreshTokenUseCase {

    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final JwtConfig jwtConfig;

    public RefreshTokenUseCaseImpl(RefreshTokenRepository refreshTokenRepository,
                                   UserRepository userRepository,
                                   JwtTokenProvider jwtTokenProvider,
                                   JwtConfig jwtConfig) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.userRepository = userRepository;
        this.jwtTokenProvider = jwtTokenProvider;
        this.jwtConfig = jwtConfig;
    }

    @Override
    public TokenResponse execute(String rawRefreshToken) {
        String hash = sha256(rawRefreshToken);
        RefreshToken token = refreshTokenRepository.findActiveByHash(hash)
            .orElseThrow(() -> new UnauthorizedException("Invalid or expired refresh token"));

        refreshTokenRepository.revoke(token.id());

        User user = userRepository.findById(token.userId())
            .orElseThrow(() -> new UnauthorizedException("User not found"));

        JwtClaims claims = new JwtClaims(user.id(), user.tenantId(), user.email(),
            user.name(), user.authProvider(), user.roles(),
            Instant.now().plusSeconds(jwtConfig.accessTokenExpirySeconds()));
        String newAccessToken = jwtTokenProvider.issueAccessToken(claims);
        String newRawRefresh = jwtTokenProvider.issueRefreshToken();
        String newHash = sha256(newRawRefresh);

        RefreshToken newToken = new RefreshToken(null, user.tenantId(), user.id(), newHash,
            null, Instant.now().plusSeconds(jwtConfig.refreshTokenExpirySeconds()), null, null, null);
        refreshTokenRepository.save(newToken);

        return new TokenResponse(newAccessToken, newRawRefresh, jwtConfig.accessTokenExpirySeconds());
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
