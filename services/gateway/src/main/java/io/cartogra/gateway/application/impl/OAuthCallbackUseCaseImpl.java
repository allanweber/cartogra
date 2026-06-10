package io.cartogra.gateway.application.impl;

import io.cartogra.gateway.api.dto.TokenResponse;
import io.cartogra.gateway.application.OAuthCallbackUseCase;
import io.cartogra.gateway.config.JwtConfig;
import io.cartogra.gateway.config.OAuthConfig;
import io.cartogra.gateway.domain.RefreshToken;
import io.cartogra.gateway.domain.Tenant;
import io.cartogra.gateway.domain.User;
import io.cartogra.gateway.domain.exception.InvalidOAuthStateException;
import io.cartogra.gateway.infrastructure.jdbc.RefreshTokenRepository;
import io.cartogra.gateway.infrastructure.jdbc.TenantRepository;
import io.cartogra.gateway.infrastructure.jdbc.UserRepository;
import io.cartogra.gateway.infrastructure.jwt.JwtClaims;
import io.cartogra.gateway.infrastructure.jwt.JwtTokenProvider;
import io.cartogra.gateway.infrastructure.oauth.OAuthProfile;
import io.cartogra.gateway.infrastructure.oauth.OAuthProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class OAuthCallbackUseCaseImpl implements OAuthCallbackUseCase {

    private final List<OAuthProvider> providers;
    private final OAuthConfig oauthConfig;
    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final JwtConfig jwtConfig;
    private final StringRedisTemplate redis;

    public OAuthCallbackUseCaseImpl(List<OAuthProvider> providers,
                                    OAuthConfig oauthConfig,
                                    UserRepository userRepository,
                                    TenantRepository tenantRepository,
                                    RefreshTokenRepository refreshTokenRepository,
                                    JwtTokenProvider jwtTokenProvider,
                                    JwtConfig jwtConfig,
                                    StringRedisTemplate redis) {
        this.providers = List.copyOf(providers);
        this.oauthConfig = oauthConfig;
        this.userRepository = userRepository;
        this.tenantRepository = tenantRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtTokenProvider = jwtTokenProvider;
        this.jwtConfig = jwtConfig;
        this.redis = redis;
    }

    @Override
    public TokenResponse execute(String provider, String code, String state) {
        String stateKey = "oauth:state:" + state;
        String stateValue = redis.opsForValue().getAndDelete(stateKey);
        if (stateValue == null) {
            throw new InvalidOAuthStateException("Invalid or expired OAuth state");
        }

        OAuthProvider oauthProvider = providers.stream()
            .filter(p -> p.providerName().equals(provider))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown OAuth provider: " + provider));

        String redirectUri = redirectUriFor(provider);
        String accessToken = oauthProvider.exchangeCodeForAccessToken(code, redirectUri);
        OAuthProfile profile = oauthProvider.fetchProfile(accessToken);

        User user;
        if ("new".equals(stateValue)) {
            var maybeExisting = userRepository.findByEmail(profile.email());
            if (maybeExisting.isPresent()) {
                user = maybeExisting.get();
            } else {
                Tenant tenant = tenantRepository.save(
                    new Tenant(null, null, profile.email(), null, "free", null, null, null));
                User newUser = new User(null, tenant.id(), profile.email(), profile.name(), provider,
                    profile.subject(), null, true, List.of("ADMIN"),
                    null, null, null, null, null, null, null);
                user = userRepository.save(newUser);
            }
        } else {
            UUID tenantId = UUID.fromString(stateValue);
            user = userRepository.findByTenantAndEmail(tenantId, profile.email())
                .map(existing -> {
                    if (provider.equals(existing.authProvider()) && !profile.subject().equals(existing.authSubject())) {
                        return new User(existing.id(), existing.tenantId(), existing.email(),
                            existing.name(), provider, profile.subject(), existing.passwordHash(),
                            true, existing.roles(), null, null,
                            existing.passwordResetToken(), existing.passwordResetTokenExp(),
                            existing.createdAt(), existing.updatedAt(), existing.deletedAt());
                    }
                    return existing;
                })
                .map(userRepository::save)
                .orElseGet(() -> {
                    User newUser = new User(null, tenantId, profile.email(), profile.name(), provider,
                        profile.subject(), null, true, List.of("VIEWER"),
                        null, null, null, null, null, null, null);
                    return userRepository.save(newUser);
                });
        }

        JwtClaims claims = new JwtClaims(user.id(), user.tenantId(), user.email(),
            user.name(), user.authProvider(), user.roles(),
            Instant.now().plusSeconds(jwtConfig.accessTokenExpirySeconds()));
        String newAccessToken = jwtTokenProvider.issueAccessToken(claims);
        String rawRefresh = jwtTokenProvider.issueRefreshToken();
        String refreshHash = sha256(rawRefresh);

        RefreshToken refreshToken = new RefreshToken(null, user.tenantId(), user.id(), refreshHash,
            null, Instant.now().plusSeconds(jwtConfig.refreshTokenExpirySeconds()), null, null, null);
        refreshTokenRepository.save(refreshToken);

        return new TokenResponse(newAccessToken, rawRefresh, jwtConfig.accessTokenExpirySeconds());
    }

    private String redirectUriFor(String provider) {
        return switch (provider) {
            case "google" -> oauthConfig.google().redirectUri();
            case "github" -> oauthConfig.github().redirectUri();
            default -> throw new IllegalArgumentException("Unknown OAuth provider: " + provider);
        };
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
