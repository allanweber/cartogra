package io.cartogra.gateway.application;

import io.cartogra.gateway.api.dto.RegisterRequest;
import io.cartogra.gateway.api.dto.RegisterResponse;

public interface RegisterUserUseCase {
    RegisterResponse execute(RegisterRequest request);
}
