package io.cartogra.gateway.infrastructure.email;

public interface EmailSender {
    void sendVerification(String toEmail, String otpToken);
    void sendPasswordReset(String toEmail, String resetToken);
}
