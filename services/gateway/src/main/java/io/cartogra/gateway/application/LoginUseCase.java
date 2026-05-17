package io.cartogra.gateway.application;

import io.cartogra.gateway.api.dto.TokenResponse;

import java.util.UUID;

public interface LoginUseCase {
    TokenResponse execute(UUID tenantId, String email, String password);
}
