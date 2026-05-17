package io.cartogra.gateway.application;

public interface VerifyEmailUseCase {
    void execute(String email, String token);
}
