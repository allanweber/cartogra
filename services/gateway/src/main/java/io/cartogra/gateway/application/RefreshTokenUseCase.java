package io.cartogra.gateway.application;

import io.cartogra.gateway.api.dto.TokenResponse;

public interface RefreshTokenUseCase {
    TokenResponse execute(String rawRefreshToken);
}
