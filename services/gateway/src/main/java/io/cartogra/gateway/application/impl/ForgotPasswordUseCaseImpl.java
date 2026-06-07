package io.cartogra.gateway.application.impl;

import io.cartogra.gateway.application.ForgotPasswordUseCase;
import io.cartogra.gateway.domain.User;
import io.cartogra.gateway.infrastructure.email.EmailSender;
import io.cartogra.gateway.infrastructure.jdbc.UserRepository;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class ForgotPasswordUseCaseImpl implements ForgotPasswordUseCase {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final ExecutorService emailExecutor = Executors.newVirtualThreadPerTaskExecutor();

    private final UserRepository userRepository;
    private final EmailSender emailSender;

    public ForgotPasswordUseCaseImpl(UserRepository userRepository, EmailSender emailSender) {
        this.userRepository = userRepository;
        this.emailSender = emailSender;
    }

    @Override
    public void execute(String email) {
        List<User> users = userRepository.findAllByEmail(email);

        // Always return success to avoid leaking whether an email exists
        for (User user : users) {
            if (!user.emailVerified()) {
                continue;
            }

            String token = generateToken();
            Instant exp = Instant.now().plusSeconds(900);

            User updated = new User(user.id(), user.tenantId(), user.email(), user.authProvider(),
                user.authSubject(), user.passwordHash(), user.emailVerified(), user.roles(),
                user.emailVerificationToken(), user.emailVerificationTokenExp(),
                token, exp,
                user.createdAt(), user.updatedAt(), user.deletedAt());
            userRepository.save(updated);

            emailExecutor.submit(() -> emailSender.sendPasswordReset(email, token));
        }
    }

    private String generateToken() {
        return String.format("%06d", SECURE_RANDOM.nextInt(1_000_000));
    }
}
