package io.cartogra.gateway.application.impl;

import io.cartogra.gateway.application.OAuthStartUseCase;
import io.cartogra.gateway.config.OAuthConfig;
import io.cartogra.gateway.infrastructure.oauth.OAuthProvider;
import org.jspecify.annotations.Nullable;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class OAuthStartUseCaseImpl implements OAuthStartUseCase {

    private final Map<String, OAuthProvider> providers;
    private final OAuthConfig oauthConfig;
    private final ReactiveStringRedisTemplate redis;

    public OAuthStartUseCaseImpl(List<OAuthProvider> providers,
                                 OAuthConfig oauthConfig,
                                 ReactiveStringRedisTemplate redis) {
        this.providers = providers.stream()
            .collect(Collectors.toMap(OAuthProvider::providerName, Function.identity()));
        this.oauthConfig = oauthConfig;
        this.redis = redis;
    }

    @Override
    public String buildAuthorizationUri(String provider, @Nullable UUID tenantId, String state) {
        OAuthProvider oauthProvider = providers.get(provider);
        if (oauthProvider == null) {
            throw new IllegalArgumentException("Unknown OAuth provider: " + provider);
        }
        String redirectUri = redirectUriFor(provider);
        String stateValue = tenantId != null ? tenantId.toString() : "new";
        redis.opsForValue()
            .set("oauth:state:" + state, stateValue, Duration.ofMinutes(10))
            .block();
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
