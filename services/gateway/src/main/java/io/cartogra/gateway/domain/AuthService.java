package io.cartogra.gateway.domain;

import io.cartogra.gateway.api.dto.RegisterRequest;
import io.cartogra.gateway.api.dto.RegisterResponse;
import io.cartogra.gateway.api.dto.TokenResponse;
import io.cartogra.gateway.api.dto.UserInfoResponse;
import io.cartogra.gateway.config.JwtConfig;
import io.cartogra.gateway.domain.exception.ConflictException;
import io.cartogra.gateway.domain.exception.InvalidOtpException;
import io.cartogra.gateway.domain.exception.UnauthorizedException;
import io.cartogra.gateway.domain.exception.UnverifiedEmailException;
import io.cartogra.gateway.infrastructure.email.EmailSender;
import io.cartogra.gateway.repository.RefreshTokenRepository;
import io.cartogra.gateway.repository.TenantRepository;
import io.cartogra.gateway.repository.UserRepository;
import io.cartogra.gateway.infrastructure.jwt.JwtClaims;
import io.cartogra.gateway.infrastructure.jwt.JwtTokenProvider;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Local-credential authentication and account lifecycle: registration, verification, login,
 *  refresh, logout, password reset, and profile updates. */
@Service
public class AuthService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final long OTP_TTL_SECONDS = 900;
    private static final ExecutorService EMAIL_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final EmailSender emailSender;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final JwtConfig jwtConfig;

    public AuthService(UserRepository userRepository,
                       TenantRepository tenantRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       EmailSender emailSender,
                       PasswordEncoder passwordEncoder,
                       JwtTokenProvider jwtTokenProvider,
                       JwtConfig jwtConfig) {
        this.userRepository = userRepository;
        this.tenantRepository = tenantRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.emailSender = emailSender;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.jwtConfig = jwtConfig;
    }

    public RegisterResponse register(RegisterRequest request) {
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
        Instant otpExp = Instant.now().plusSeconds(OTP_TTL_SECONDS);
        String hash = passwordEncoder.encode(request.password());

        User user = new User(null, tenant.id(), request.email(), null, "local", null, hash,
            false, List.of("ADMIN"), otp, otpExp, null, null, null, null, null);
        userRepository.save(user);

        EMAIL_EXECUTOR.submit(() -> emailSender.sendVerification(request.email(), otp));

        return new RegisterResponse(tenant.id());
    }

    public void verifyEmail(String email, String token) {
        User user = userRepository.findByVerificationToken(token)
            .orElseThrow(() -> new InvalidOtpException("Invalid or expired verification token"));

        if (!user.email().equals(email)) {
            throw new InvalidOtpException("Token does not match email");
        }
        if (user.emailVerificationTokenExp() != null && user.emailVerificationTokenExp().isBefore(Instant.now())) {
            throw new InvalidOtpException("Verification token has expired");
        }

        User verified = new User(user.id(), user.tenantId(), user.email(), user.name(),
            user.authProvider(), user.authSubject(), user.passwordHash(), true, user.roles(),
            null, null, user.passwordResetToken(), user.passwordResetTokenExp(),
            user.createdAt(), user.updatedAt(), user.deletedAt());
        userRepository.save(verified);
    }

    public void resendVerification(String email) {
        for (User user : userRepository.findAllByEmail(email)) {
            if (user.emailVerified()) {
                continue;
            }
            String otp = generateOtp();
            Instant exp = Instant.now().plusSeconds(OTP_TTL_SECONDS);

            User updated = new User(user.id(), user.tenantId(), user.email(), user.name(),
                user.authProvider(), user.authSubject(), user.passwordHash(), user.emailVerified(),
                user.roles(), otp, exp, user.passwordResetToken(), user.passwordResetTokenExp(),
                user.createdAt(), user.updatedAt(), user.deletedAt());
            userRepository.save(updated);

            EMAIL_EXECUTOR.submit(() -> emailSender.sendVerification(email, otp));
        }
    }

    public TokenResponse login(String email, String password) {
        User user = userRepository.findByEmail(email)
            .orElseThrow(() -> new UnauthorizedException("Invalid credentials"));

        if (user.deletedAt() != null) {
            throw new UnauthorizedException("Invalid credentials");
        }
        if (user.passwordHash() == null || !passwordEncoder.matches(password, user.passwordHash())) {
            throw new UnauthorizedException("Invalid credentials");
        }
        if (!user.emailVerified()) {
            throw new UnverifiedEmailException();
        }

        return issueTokens(user);
    }

    public TokenResponse refreshToken(String rawRefreshToken) {
        String hash = sha256(rawRefreshToken);
        RefreshToken token = refreshTokenRepository.findActiveByHash(hash)
            .orElseThrow(() -> new UnauthorizedException("Invalid or expired refresh token"));

        refreshTokenRepository.revoke(token.id());

        User user = userRepository.findById(token.userId())
            .orElseThrow(() -> new UnauthorizedException("User not found"));

        return issueTokens(user);
    }

    public void logout(String rawRefreshToken) {
        String hash = sha256(rawRefreshToken);
        refreshTokenRepository.findActiveByHash(hash)
            .ifPresent(token -> refreshTokenRepository.revoke(token.id()));
    }

    public void forgotPassword(String email) {
        // Always succeed to avoid leaking whether an email exists.
        for (User user : userRepository.findAllByEmail(email)) {
            if (!user.emailVerified()) {
                continue;
            }
            String token = generateOtp();
            Instant exp = Instant.now().plusSeconds(OTP_TTL_SECONDS);

            User updated = new User(user.id(), user.tenantId(), user.email(), user.name(),
                user.authProvider(), user.authSubject(), user.passwordHash(), user.emailVerified(),
                user.roles(), user.emailVerificationToken(), user.emailVerificationTokenExp(),
                token, exp, user.createdAt(), user.updatedAt(), user.deletedAt());
            userRepository.save(updated);

            EMAIL_EXECUTOR.submit(() -> emailSender.sendPasswordReset(email, token));
        }
    }

    public void resetPassword(String token, String newPassword) {
        User user = userRepository.findByPasswordResetToken(token)
            .orElseThrow(() -> new InvalidOtpException("Invalid or expired reset token"));

        if (user.passwordResetTokenExp() == null || user.passwordResetTokenExp().isBefore(Instant.now())) {
            throw new InvalidOtpException("Reset token has expired");
        }

        String newHash = passwordEncoder.encode(newPassword);
        User updated = new User(user.id(), user.tenantId(), user.email(), user.name(),
            user.authProvider(), user.authSubject(), newHash, user.emailVerified(), user.roles(),
            user.emailVerificationToken(), user.emailVerificationTokenExp(),
            null, null, user.createdAt(), user.updatedAt(), user.deletedAt());
        userRepository.save(updated);
    }

    public UpdateUserInfoResult updateUserInfo(UUID userId, String rawName, String rawEmail) {
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
            verificationTokenExp = Instant.now().plusSeconds(OTP_TTL_SECONDS);
            String otpSnapshot = verificationToken;
            String emailSnapshot = newEmail;
            EMAIL_EXECUTOR.submit(() -> emailSender.sendVerification(emailSnapshot, otpSnapshot));
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

    private TokenResponse issueTokens(User user) {
        JwtClaims claims = new JwtClaims(user.id(), user.tenantId(), user.email(),
            user.name(), user.authProvider(), user.roles(),
            Instant.now().plusSeconds(jwtConfig.accessTokenExpirySeconds()));
        String accessToken = jwtTokenProvider.issueAccessToken(claims);
        String rawRefresh = jwtTokenProvider.issueRefreshToken();

        RefreshToken token = new RefreshToken(null, user.tenantId(), user.id(), sha256(rawRefresh),
            null, Instant.now().plusSeconds(jwtConfig.refreshTokenExpirySeconds()), null, null, null);
        refreshTokenRepository.save(token);

        return new TokenResponse(accessToken, rawRefresh, jwtConfig.accessTokenExpirySeconds());
    }

    private static String generateOtp() {
        return String.format("%06d", SECURE_RANDOM.nextInt(1_000_000));
    }

    static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
