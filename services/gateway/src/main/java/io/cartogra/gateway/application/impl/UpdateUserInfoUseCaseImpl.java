package io.cartogra.gateway.application.impl;

import io.cartogra.gateway.api.dto.UserInfoResponse;
import io.cartogra.gateway.application.UpdateUserInfoResult;
import io.cartogra.gateway.application.UpdateUserInfoUseCase;
import io.cartogra.gateway.config.JwtConfig;
import io.cartogra.gateway.domain.User;
import io.cartogra.gateway.domain.exception.ConflictException;
import io.cartogra.gateway.domain.exception.UnauthorizedException;
import io.cartogra.gateway.infrastructure.email.EmailSender;
import io.cartogra.gateway.infrastructure.jdbc.UserRepository;
import io.cartogra.gateway.infrastructure.jwt.JwtClaims;
import io.cartogra.gateway.infrastructure.jwt.JwtTokenProvider;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class UpdateUserInfoUseCaseImpl implements UpdateUserInfoUseCase {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final ExecutorService emailExecutor = Executors.newVirtualThreadPerTaskExecutor();

    private final UserRepository userRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final JwtConfig jwtConfig;
    private final EmailSender emailSender;

    public UpdateUserInfoUseCaseImpl(UserRepository userRepository,
                                     JwtTokenProvider jwtTokenProvider,
                                     JwtConfig jwtConfig,
                                     EmailSender emailSender) {
        this.userRepository = userRepository;
        this.jwtTokenProvider = jwtTokenProvider;
        this.jwtConfig = jwtConfig;
        this.emailSender = emailSender;
    }

    @Override
    public UpdateUserInfoResult execute(UUID userId, String rawName, String rawEmail) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new UnauthorizedException("Authentication required"));

        String trimmedName = rawName != null ? rawName.trim() : null;
        String newName = (trimmedName != null && !trimmedName.isEmpty()) ? trimmedName : null;

        String trimmedEmail = rawEmail != null ? rawEmail.trim() : null;
        String newEmail = (trimmedEmail != null && !trimmedEmail.isEmpty()) ? trimmedEmail : user.email();

        boolean emailChanging = !user.email().equals(newEmail);
        boolean emailVerified = user.emailVerified();
        String verificationToken = user.emailVerificationToken();
        Instant verificationTokenExp = user.emailVerificationTokenExp();

        if (emailChanging) {
            userRepository.findByTenantAndEmail(user.tenantId(), newEmail)
                .filter(existing -> !existing.id().equals(user.id()))
                .ifPresent(_ -> { throw new ConflictException("Email is already in use."); });

            emailVerified = false;
            verificationToken = generateOtp();
            verificationTokenExp = Instant.now().plusSeconds(900);
            String otpSnapshot = verificationToken;
            String emailSnapshot = newEmail;
            emailExecutor.submit(() -> emailSender.sendVerification(emailSnapshot, otpSnapshot));
        }

        User updated = new User(user.id(), user.tenantId(), newEmail, newName, user.authProvider(),
            user.authSubject(), user.passwordHash(), emailVerified,
            user.roles(), verificationToken, verificationTokenExp,
            user.passwordResetToken(), user.passwordResetTokenExp(),
            user.createdAt(), user.updatedAt(), user.deletedAt());

        userRepository.save(updated);

        long expiresIn = jwtConfig.accessTokenExpirySeconds();
        JwtClaims claims = new JwtClaims(updated.id(), updated.tenantId(), newEmail,
            newName, updated.authProvider(), updated.roles(),
            Instant.now().plusSeconds(expiresIn));
        String accessToken = jwtTokenProvider.issueAccessToken(claims);

        UserInfoResponse info = new UserInfoResponse(updated.id(), newEmail, newName,
            updated.authProvider(), updated.tenantId(), updated.roles());

        return new UpdateUserInfoResult(info, accessToken, expiresIn);
    }

    private String generateOtp() {
        return String.format("%06d", SECURE_RANDOM.nextInt(1_000_000));
    }
}
