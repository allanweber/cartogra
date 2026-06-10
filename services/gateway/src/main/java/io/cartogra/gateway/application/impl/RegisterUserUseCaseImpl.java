package io.cartogra.gateway.application.impl;

import io.cartogra.gateway.api.dto.RegisterRequest;
import io.cartogra.gateway.api.dto.RegisterResponse;
import io.cartogra.gateway.application.RegisterUserUseCase;
import io.cartogra.gateway.domain.Tenant;
import io.cartogra.gateway.domain.User;
import io.cartogra.gateway.domain.exception.ConflictException;
import io.cartogra.gateway.infrastructure.email.EmailSender;
import io.cartogra.gateway.infrastructure.jdbc.TenantRepository;
import io.cartogra.gateway.infrastructure.jdbc.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class RegisterUserUseCaseImpl implements RegisterUserUseCase {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final ExecutorService emailExecutor = Executors.newVirtualThreadPerTaskExecutor();

    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final EmailSender emailSender;
    private final PasswordEncoder passwordEncoder;

    public RegisterUserUseCaseImpl(UserRepository userRepository,
                                   TenantRepository tenantRepository,
                                   EmailSender emailSender,
                                   PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.tenantRepository = tenantRepository;
        this.emailSender = emailSender;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public RegisterResponse execute(RegisterRequest request) {
        userRepository.findByEmail(request.email()).ifPresent(existing -> {
            if (!"local".equals(existing.authProvider())) {
                throw new ConflictException(
                    "This email is linked to a %s account. Please sign in with %s."
                        .formatted(existing.authProvider(), existing.authProvider()));
            }
            throw new ConflictException("An account with this email already exists.");
        });

        String name = (request.orgName() != null && !request.orgName().isBlank())
            ? request.orgName()
            : request.email();

        Tenant tenant = tenantRepository.save(new Tenant(null, null, name, null, "free", null, null, null));

        String otp = generateOtp();
        Instant otpExp = Instant.now().plusSeconds(900);
        String hash = passwordEncoder.encode(request.password());

        User user = new User(null, tenant.id(), request.email(), null, "local", null, hash,
            false, List.of("ADMIN"), otp, otpExp, null, null, null, null, null);
        userRepository.save(user);

        emailExecutor.submit(() -> emailSender.sendVerification(request.email(), otp));

        return new RegisterResponse(tenant.id());
    }

    private String generateOtp() {
        return String.format("%06d", SECURE_RANDOM.nextInt(1_000_000));
    }
}
