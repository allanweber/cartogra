package io.cartogra.gateway.application.impl;

import io.cartogra.gateway.application.ResetPasswordUseCase;
import io.cartogra.gateway.domain.User;
import io.cartogra.gateway.domain.exception.InvalidOtpException;
import io.cartogra.gateway.infrastructure.jdbc.UserRepository;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class ResetPasswordUseCaseImpl implements ResetPasswordUseCase {

    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    public ResetPasswordUseCaseImpl(UserRepository userRepository) {
        this.userRepository = userRepository;
        this.passwordEncoder = new BCryptPasswordEncoder();
    }

    @Override
    public void execute(String token, String newPassword) {
        User user = userRepository.findByPasswordResetToken(token)
            .orElseThrow(() -> new InvalidOtpException("Invalid or expired reset token"));

        if (user.passwordResetTokenExp() == null || user.passwordResetTokenExp().isBefore(Instant.now())) {
            throw new InvalidOtpException("Reset token has expired");
        }

        String newHash = passwordEncoder.encode(newPassword);
        User updated = new User(user.id(), user.tenantId(), user.email(), user.authProvider(),
            user.authSubject(), newHash, user.emailVerified(), user.roles(),
            user.emailVerificationToken(), user.emailVerificationTokenExp(),
            null, null,
            user.createdAt(), user.updatedAt(), user.deletedAt());
        userRepository.save(updated);
    }
}
