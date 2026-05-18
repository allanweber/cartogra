package io.cartogra.gateway.application;

import org.jspecify.annotations.Nullable;

import java.util.UUID;

public interface OAuthStartUseCase {
    String buildAuthorizationUri(String provider, @Nullable UUID tenantId, String state);
}
