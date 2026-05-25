package io.cartogra.gateway.application;

public interface ResetPasswordUseCase {
    void execute(String token, String newPassword);
}
