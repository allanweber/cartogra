package io.cartogra.gateway.application;

import io.cartogra.gateway.api.dto.TokenResponse;

public interface OAuthCallbackUseCase {
    TokenResponse execute(String provider, String code, String state);
}
