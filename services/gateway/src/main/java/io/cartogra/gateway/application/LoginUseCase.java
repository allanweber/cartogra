package io.cartogra.gateway.application;

import io.cartogra.gateway.api.dto.TokenResponse;

public interface LoginUseCase {
    TokenResponse execute(String email, String password);
}
