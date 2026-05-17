package io.cartogra.gateway.application.impl;

import io.cartogra.gateway.application.VerifyEmailUseCase;
import io.cartogra.gateway.domain.User;
import io.cartogra.gateway.domain.exception.InvalidOtpException;
import io.cartogra.gateway.infrastructure.jdbc.UserRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class VerifyEmailUseCaseImpl implements VerifyEmailUseCase {

    private final UserRepository userRepository;

    public VerifyEmailUseCaseImpl(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public void execute(String email, String token) {
        User user = userRepository.findByVerificationToken(token)
            .orElseThrow(() -> new InvalidOtpException("Invalid or expired verification token"));

        if (!user.email().equals(email)) {
            throw new InvalidOtpException("Token does not match email");
        }

        if (user.emailVerificationTokenExp() != null && user.emailVerificationTokenExp().isBefore(Instant.now())) {
            throw new InvalidOtpException("Verification token has expired");
        }

        User verified = new User(user.id(), user.tenantId(), user.email(), user.authProvider(),
            user.authSubject(), user.passwordHash(), true, user.roles(),
            null, null, user.createdAt(), user.updatedAt(), user.deletedAt());
        userRepository.save(verified);
    }
}
