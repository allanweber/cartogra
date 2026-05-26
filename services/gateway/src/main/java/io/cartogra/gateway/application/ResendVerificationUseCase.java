package io.cartogra.gateway.application;

public interface ResendVerificationUseCase {
    void execute(String email);
}
