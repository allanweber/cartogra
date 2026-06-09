package io.cartogra.gateway.application;

import java.util.UUID;

public interface UpdateUserInfoUseCase {
    UpdateUserInfoResult execute(UUID userId, String name, String email);
}
