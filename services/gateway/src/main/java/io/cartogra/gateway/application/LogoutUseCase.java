package io.cartogra.gateway.application;

public interface LogoutUseCase {
    void execute(String rawRefreshToken);
}
