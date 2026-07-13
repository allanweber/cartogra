package io.cartogra.gateway.domain;

import io.cartogra.gateway.api.dto.AcceptInviteRequest;
import io.cartogra.gateway.api.dto.InviteUserRequest;
import io.cartogra.gateway.api.dto.InviteUserResponse;
import io.cartogra.gateway.api.dto.RegisterRequest;
import io.cartogra.gateway.api.dto.RegisterResponse;
import io.cartogra.gateway.api.dto.TokenResponse;
import io.cartogra.gateway.api.dto.UserInfoResponse;
import io.cartogra.gateway.config.JwtConfig;
import io.cartogra.gateway.domain.exception.ConflictException;
import io.cartogra.gateway.domain.exception.InvalidOtpException;
import io.cartogra.gateway.domain.exception.NotFoundException;
import io.cartogra.gateway.domain.exception.PlanLimitExceededException;
import io.cartogra.gateway.domain.exception.UnauthorizedException;
import io.cartogra.gateway.domain.exception.UnverifiedEmailException;
import io.cartogra.gateway.infrastructure.email.EmailSender;
import io.cartogra.gateway.repository.InvitationRepository;
import io.cartogra.gateway.repository.RefreshTokenRepository;
import io.cartogra.gateway.repository.TeamMembershipRepository;
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
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Local-credential authentication and account lifecycle: registration, verification, login,
 *  refresh, logout, password reset, and profile updates. */
@Service
public class AuthService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final long OTP_TTL_SECONDS = 900;
    static final long INVITE_TOKEN_TTL_SECONDS = 7 * 24 * 3600;
    private static final ExecutorService EMAIL_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final TeamMembershipRepository teamMembershipRepository;
    private final InvitationRepository invitationRepository;
    private final EmailSender emailSender;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final JwtConfig jwtConfig;
    private final BillingPlanService billingPlanService;

    public AuthService(UserRepository userRepository,
                       TenantRepository tenantRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       TeamMembershipRepository teamMembershipRepository,
                       InvitationRepository invitationRepository,
                       EmailSender emailSender,
                       PasswordEncoder passwordEncoder,
                       JwtTokenProvider jwtTokenProvider,
                       JwtConfig jwtConfig,
                       BillingPlanService billingPlanService) {
        this.userRepository = userRepository;
        this.tenantRepository = tenantRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.teamMembershipRepository = teamMembershipRepository;
        this.invitationRepository = invitationRepository;
        this.emailSender = emailSender;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.jwtConfig = jwtConfig;
        this.billingPlanService = billingPlanService;
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
            false, List.of("ADMIN"), otp, otpExp, null, null, null, null, null, null);
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
            user.createdAt(), user.updatedAt(), user.deletedAt(), user.disabledAt());
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
                user.createdAt(), user.updatedAt(), user.deletedAt(), user.disabledAt());
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
        if (user.disabledAt() != null) {
            throw new UnauthorizedException("This account has been disabled. Contact your administrator.");
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

        if (user.disabledAt() != null) {
            throw new UnauthorizedException("This account has been disabled. Contact your administrator.");
        }

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
                token, exp, user.createdAt(), user.updatedAt(), user.deletedAt(), user.disabledAt());
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
            null, null, user.createdAt(), user.updatedAt(), user.deletedAt(), user.disabledAt());
        userRepository.save(updated);
    }

    public List<User> listUsers(UUID tenantId) {
        return userRepository.findAllByTenant(tenantId);
    }

    public List<User> listUsersByIds(UUID tenantId, Set<UUID> ids) {
        return userRepository.findByIds(tenantId, ids);
    }

    public User updateUserRole(UUID tenantId, UUID targetUserId, String role) {
        User user = userRepository.findById(targetUserId)
            .filter(u -> u.tenantId().equals(tenantId))
            .orElseThrow(() -> new NotFoundException("User not found: " + targetUserId));

        if (user.roles().contains("ADMIN") && !"ADMIN".equals(role) && countAdmins(tenantId) <= 1) {
            throw new IllegalArgumentException("Cannot change role: this is the tenant's last admin.");
        }

        User updated = new User(user.id(), user.tenantId(), user.email(), user.name(),
            user.authProvider(), user.authSubject(), user.passwordHash(), user.emailVerified(),
            List.of(role), user.emailVerificationToken(), user.emailVerificationTokenExp(),
            user.passwordResetToken(), user.passwordResetTokenExp(),
            user.createdAt(), user.updatedAt(), user.deletedAt(), user.disabledAt());
        return userRepository.save(updated);
    }

    public void removeUser(UUID tenantId, UUID requestingUserId, UUID targetUserId) {
        if (requestingUserId.equals(targetUserId)) {
            throw new IllegalArgumentException("Cannot remove your own account.");
        }
        User user = userRepository.findById(targetUserId)
            .filter(u -> u.tenantId().equals(tenantId))
            .orElseThrow(() -> new NotFoundException("User not found: " + targetUserId));

        if (user.roles().contains("ADMIN") && countAdmins(tenantId) <= 1) {
            throw new IllegalArgumentException("Cannot remove the tenant's last admin.");
        }

        userRepository.softDelete(tenantId, targetUserId);
    }

    public User setUserDisabled(UUID tenantId, UUID requestingUserId, UUID targetUserId, boolean disabled) {
        if (disabled && requestingUserId.equals(targetUserId)) {
            throw new IllegalArgumentException("Cannot disable your own account.");
        }
        User user = userRepository.findById(targetUserId)
            .filter(u -> u.tenantId().equals(tenantId))
            .orElseThrow(() -> new NotFoundException("User not found: " + targetUserId));

        if (disabled && user.roles().contains("ADMIN") && countAdmins(tenantId) <= 1) {
            throw new IllegalArgumentException("Cannot disable the tenant's last admin.");
        }

        User updated = new User(user.id(), user.tenantId(), user.email(), user.name(),
            user.authProvider(), user.authSubject(), user.passwordHash(), user.emailVerified(),
            user.roles(), user.emailVerificationToken(), user.emailVerificationTokenExp(),
            user.passwordResetToken(), user.passwordResetTokenExp(),
            user.createdAt(), user.updatedAt(), user.deletedAt(),
            disabled ? Instant.now() : null);
        return userRepository.save(updated);
    }

    private long countAdmins(UUID tenantId) {
        return userRepository.findAllByTenant(tenantId).stream()
            .filter(u -> u.roles().contains("ADMIN"))
            .count();
    }

    public InviteUserResponse inviteUser(UUID tenantId, UUID invitedBy, InviteUserRequest request) {
        userRepository.findByTenantAndEmail(tenantId, request.email()).ifPresent(_ -> {
            throw new ConflictException("An account with this email already exists in this tenant.");
        });

        Tenant tenant = tenantRepository.findByTenantId(tenantId)
            .orElseThrow(() -> new NotFoundException("Tenant not found: " + tenantId));
        int maxUsers = billingPlanService.getBySlug(tenant.plan()).maxUsers();
        if (maxUsers != BillingPlan.UNLIMITED && userRepository.count(tenantId) >= maxUsers) {
            throw new PlanLimitExceededException("users", maxUsers);
        }

        if (request.teamId() != null && !teamMembershipRepository.teamExists(tenantId, request.teamId())) {
            throw new NotFoundException("Team not found: " + request.teamId());
        }

        String inviteToken = generateInviteToken();
        Instant inviteExp = Instant.now().plusSeconds(INVITE_TOKEN_TTL_SECONDS);

        Invitation invitation = new Invitation(null, tenantId, request.email(), request.role(),
            invitedBy, request.teamId(), inviteToken, inviteExp, "PENDING", null, null);
        Invitation saved = invitationRepository.save(invitation);

        EMAIL_EXECUTOR.submit(() -> emailSender.sendInvite(request.email(), tenant.name(), inviteToken));

        return new InviteUserResponse(saved.id(), saved.email());
    }

    public TokenResponse acceptInvite(AcceptInviteRequest request) {
        Invitation invitation = invitationRepository.findByToken(request.token())
            .orElseThrow(() -> new InvalidOtpException("Invalid or expired invite token"));

        if (!"PENDING".equals(invitation.status())) {
            throw new InvalidOtpException("Invite has already been used");
        }
        if (invitation.tokenExp() == null || invitation.tokenExp().isBefore(Instant.now())) {
            throw new InvalidOtpException("Invite token has expired");
        }

        userRepository.findByTenantAndEmail(invitation.tenantId(), invitation.email()).ifPresent(_ -> {
            throw new ConflictException("An account with this email already exists in this tenant.");
        });

        String hash = passwordEncoder.encode(request.password());
        User user = new User(null, invitation.tenantId(), invitation.email(), null, "local", null,
            hash, true, List.of(invitation.role()), null, null, null, null, null, null, null, null);
        User saved = userRepository.save(user);

        if (invitation.teamId() != null) {
            teamMembershipRepository.addMember(saved.tenantId(), invitation.teamId(), saved.id());
        }

        invitationRepository.markAccepted(invitation.id());

        return issueTokens(saved);
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
            user.createdAt(), user.updatedAt(), user.deletedAt(), user.disabledAt());
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

    static String generateInviteToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
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
