package io.cartogra.gateway.application.impl;

import io.cartogra.gateway.application.OAuthStartUseCase;
import io.cartogra.gateway.config.OAuthConfig;
import io.cartogra.gateway.infrastructure.oauth.OAuthProvider;
import org.jspecify.annotations.Nullable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Service
public class OAuthStartUseCaseImpl implements OAuthStartUseCase {

    private final List<OAuthProvider> providers;
    private final OAuthConfig oauthConfig;
    private final StringRedisTemplate redis;

    public OAuthStartUseCaseImpl(List<OAuthProvider> providers,
                                 OAuthConfig oauthConfig,
                                 StringRedisTemplate redis) {
        this.providers = List.copyOf(providers);
        this.oauthConfig = oauthConfig;
        this.redis = redis;
    }

    @Override
    public String buildAuthorizationUri(String provider, @Nullable UUID tenantId, String state) {
        OAuthProvider oauthProvider = providers.stream()
            .filter(p -> p.providerName().equals(provider))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown OAuth provider: " + provider));
        String redirectUri = redirectUriFor(provider);
        String stateValue = tenantId != null ? tenantId.toString() : "new";
        redis.opsForValue().set("oauth:state:" + state, stateValue, Duration.ofMinutes(10));
        return oauthProvider.buildAuthorizationUri(state, redirectUri);
    }

    private String redirectUriFor(String provider) {
        return switch (provider) {
            case "google" -> oauthConfig.google().redirectUri();
            case "github" -> oauthConfig.github().redirectUri();
            default -> throw new IllegalArgumentException("Unknown OAuth provider: " + provider);
        };
    }
}
