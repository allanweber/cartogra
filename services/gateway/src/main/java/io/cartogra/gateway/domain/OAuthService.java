package io.cartogra.gateway.domain;

import io.cartogra.gateway.api.dto.TokenResponse;
import io.cartogra.gateway.config.JwtConfig;
import io.cartogra.gateway.config.OAuthConfig;
import io.cartogra.gateway.domain.exception.InvalidOAuthStateException;
import io.cartogra.gateway.domain.exception.PlanLimitExceededException;
import io.cartogra.gateway.domain.exception.UnauthorizedException;
import io.cartogra.gateway.domain.exception.UnverifiedEmailException;
import io.cartogra.gateway.repository.RefreshTokenRepository;
import io.cartogra.gateway.repository.TenantRepository;
import io.cartogra.gateway.repository.UserRepository;
import io.cartogra.gateway.infrastructure.jwt.JwtClaims;
import io.cartogra.gateway.infrastructure.jwt.JwtTokenProvider;
import io.cartogra.gateway.infrastructure.oauth.OAuthProfile;
import io.cartogra.gateway.infrastructure.oauth.OAuthProvider;
import org.jspecify.annotations.Nullable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** OAuth/OIDC authorization-code flow: builds provider authorization URIs and exchanges callbacks
 *  for first-party tokens, provisioning tenant/user on first sign-in. */
@Service
public class OAuthService {

    private static final Duration STATE_TTL = Duration.ofMinutes(10);

    private final List<OAuthProvider> providers;
    private final OAuthConfig oauthConfig;
    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final JwtConfig jwtConfig;
    private final StringRedisTemplate redis;
    private final BillingPlanService billingPlanService;

    public OAuthService(List<OAuthProvider> providers,
                        OAuthConfig oauthConfig,
                        UserRepository userRepository,
                        TenantRepository tenantRepository,
                        RefreshTokenRepository refreshTokenRepository,
                        JwtTokenProvider jwtTokenProvider,
                        JwtConfig jwtConfig,
                        StringRedisTemplate redis,
                        BillingPlanService billingPlanService) {
        this.providers = List.copyOf(providers);
        this.oauthConfig = oauthConfig;
        this.userRepository = userRepository;
        this.tenantRepository = tenantRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtTokenProvider = jwtTokenProvider;
        this.jwtConfig = jwtConfig;
        this.redis = redis;
        this.billingPlanService = billingPlanService;
    }

    public String buildAuthorizationUri(String provider, @Nullable UUID tenantId, String state) {
        OAuthProvider oauthProvider = providerFor(provider);
        String redirectUri = redirectUriFor(provider);
        String stateValue = tenantId != null ? tenantId.toString() : "new";
        redis.opsForValue().set("oauth:state:" + state, stateValue, STATE_TTL);
        return oauthProvider.buildAuthorizationUri(state, redirectUri);
    }

    public TokenResponse handleCallback(String provider, String code, String state) {
        String stateKey = "oauth:state:" + state;
        String stateValue = redis.opsForValue().getAndDelete(stateKey);
        if (stateValue == null) {
            throw new InvalidOAuthStateException("Invalid or expired OAuth state");
        }

        OAuthProvider oauthProvider = providerFor(provider);
        String redirectUri = redirectUriFor(provider);
        String accessToken = oauthProvider.exchangeCodeForAccessToken(code, redirectUri);
        OAuthProfile profile = oauthProvider.fetchProfile(accessToken);

        User user = "new".equals(stateValue)
            ? resolveNewTenantUser(provider, profile)
            : resolveExistingTenantUser(UUID.fromString(stateValue), provider, profile);

        if (user.disabledAt() != null) {
            throw new UnauthorizedException("This account has been disabled. Contact your administrator.");
        }

        JwtClaims claims = new JwtClaims(user.id(), user.tenantId(), user.email(),
            user.name(), user.authProvider(), user.roles(),
            Instant.now().plusSeconds(jwtConfig.accessTokenExpirySeconds()));
        String newAccessToken = jwtTokenProvider.issueAccessToken(claims);
        String rawRefresh = jwtTokenProvider.issueRefreshToken();

        RefreshToken refreshToken = new RefreshToken(null, user.tenantId(), user.id(),
            AuthService.sha256(rawRefresh), null,
            Instant.now().plusSeconds(jwtConfig.refreshTokenExpirySeconds()), null, null, null);
        refreshTokenRepository.save(refreshToken);

        return new TokenResponse(newAccessToken, rawRefresh, jwtConfig.accessTokenExpirySeconds());
    }

    private User resolveNewTenantUser(String provider, OAuthProfile profile) {
        return userRepository.findByEmail(profile.email()).map(existing -> {
            if ("local".equals(existing.authProvider()) && !existing.emailVerified()) {
                throw new UnverifiedEmailException();
            }
            return existing;
        }).orElseGet(() -> {
            Tenant tenant = tenantRepository.save(
                new Tenant(null, null, profile.email(), null, "free", null, null, null));
            return userRepository.save(new User(null, tenant.id(), profile.email(), profile.name(),
                provider, profile.subject(), null, true, List.of("ADMIN"),
                null, null, null, null, null, null, null, null));
        });
    }

    private User resolveExistingTenantUser(UUID tenantId, String provider, OAuthProfile profile) {
        return userRepository.findByTenantAndEmail(tenantId, profile.email())
            .map(existing -> {
                if ("local".equals(existing.authProvider()) && !existing.emailVerified()) {
                    throw new UnverifiedEmailException();
                }
                if (provider.equals(existing.authProvider()) && !profile.subject().equals(existing.authSubject())) {
                    return new User(existing.id(), existing.tenantId(), existing.email(),
                        existing.name(), provider, profile.subject(), existing.passwordHash(),
                        true, existing.roles(), null, null,
                        existing.passwordResetToken(), existing.passwordResetTokenExp(),
                        existing.createdAt(), existing.updatedAt(), existing.deletedAt(),
                        existing.disabledAt());
                }
                return existing;
            })
            .map(userRepository::save)
            .orElseGet(() -> {
                Tenant tenant = tenantRepository.findByTenantId(tenantId)
                    .orElseThrow(() -> new IllegalStateException("Tenant not found: " + tenantId));
                int maxUsers = billingPlanService.getBySlug(tenant.plan()).maxUsers();
                if (maxUsers != BillingPlan.UNLIMITED && userRepository.count(tenantId) >= maxUsers) {
                    throw new PlanLimitExceededException("users", maxUsers);
                }
                return userRepository.save(new User(null, tenantId, profile.email(),
                    profile.name(), provider, profile.subject(), null, true, List.of("VIEWER"),
                    null, null, null, null, null, null, null, null));
            });
    }

    private OAuthProvider providerFor(String provider) {
        return providers.stream()
            .filter(p -> p.providerName().equals(provider))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown OAuth provider: " + provider));
    }

    private String redirectUriFor(String provider) {
        return switch (provider) {
            case "google" -> oauthConfig.google().redirectUri();
            case "github" -> oauthConfig.github().redirectUri();
            default -> throw new IllegalArgumentException("Unknown OAuth provider: " + provider);
        };
    }
}
