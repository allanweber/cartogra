package io.cartogra.gateway.application.impl;

import io.cartogra.gateway.application.ResendVerificationUseCase;
import io.cartogra.gateway.domain.User;
import io.cartogra.gateway.infrastructure.email.EmailSender;
import io.cartogra.gateway.infrastructure.jdbc.UserRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class ResendVerificationUseCaseImpl implements ResendVerificationUseCase {

    private static final ExecutorService emailExecutor = Executors.newVirtualThreadPerTaskExecutor();

    private final UserRepository userRepository;
    private final EmailSender emailSender;

    public ResendVerificationUseCaseImpl(UserRepository userRepository, EmailSender emailSender) {
        this.userRepository = userRepository;
        this.emailSender = emailSender;
    }

    @Override
    public void execute(String email) {
        List<User> users = userRepository.findAllByEmail(email);

        for (User user : users) {
            if (user.emailVerified()) {
                continue;
            }

            String otp = generateOtp();
            Instant exp = Instant.now().plusSeconds(900);

            User updated = new User(user.id(), user.tenantId(), user.email(), user.authProvider(),
                user.authSubject(), user.passwordHash(), user.emailVerified(), user.roles(),
                otp, exp,
                user.passwordResetToken(), user.passwordResetTokenExp(),
                user.createdAt(), user.updatedAt(), user.deletedAt());
            userRepository.save(updated);

            emailExecutor.submit(() -> emailSender.sendVerification(email, otp));
        }
    }

    private String generateOtp() {
        return String.format("%06d", (int) (Math.random() * 1_000_000));
    }
}
