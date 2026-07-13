package io.cartogra.gateway.infrastructure.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.resend.test-mode", havingValue = "true")
public class NoOpEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(NoOpEmailSender.class);

    @Override
    public void sendVerification(String toEmail, String otpToken) {
        log.info("[TEST MODE] Verification OTP for {} — token: {}", toEmail, otpToken);
    }

    @Override
    public void sendPasswordReset(String toEmail, String resetToken) {
        log.info("[TEST MODE] Password reset for {} — token: {}", toEmail, resetToken);
    }

    @Override
    public void sendInvite(String toEmail, String tenantName, String inviteToken) {
        log.info("[TEST MODE] Invite for {} to tenant {} — token: {}", toEmail, tenantName, inviteToken);
    }
}
