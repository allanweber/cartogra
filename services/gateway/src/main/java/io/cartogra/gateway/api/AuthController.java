package io.cartogra.gateway.api;

import io.cartogra.gateway.api.dto.AcceptInviteRequest;
import io.cartogra.gateway.api.dto.ApiResponse;
import io.cartogra.gateway.api.dto.ForgotPasswordRequest;
import io.cartogra.gateway.api.dto.InviteUserRequest;
import io.cartogra.gateway.api.dto.InviteUserResponse;
import io.cartogra.gateway.api.dto.LoginRequest;
import io.cartogra.gateway.api.dto.RegisterRequest;
import io.cartogra.gateway.api.dto.RegisterResponse;
import io.cartogra.gateway.api.dto.ResendVerificationRequest;
import io.cartogra.gateway.api.dto.ResetPasswordRequest;
import io.cartogra.gateway.api.dto.TokenResponse;
import io.cartogra.gateway.api.dto.UpdateUserInfoRequest;
import io.cartogra.gateway.api.dto.UpdateUserRoleRequest;
import io.cartogra.gateway.api.dto.UserInfoResponse;
import io.cartogra.gateway.api.dto.UserSummaryResponse;
import io.cartogra.gateway.api.dto.VerifyEmailRequest;
import io.cartogra.gateway.config.JwtConfig;
import io.cartogra.gateway.domain.AuthService;
import io.cartogra.gateway.domain.UpdateUserInfoResult;
import io.cartogra.gateway.domain.exception.UnauthorizedException;
import io.cartogra.gateway.infrastructure.jwt.JwtClaims;
import io.cartogra.gateway.infrastructure.tracing.TraceContext;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final String COOKIE_JWT = "jwt";
    private static final String COOKIE_REFRESH = "jwt_refresh";

    private final AuthService auth;
    private final TraceContext traceContext;
    private final boolean cookieSecure;

    public AuthController(AuthService auth, TraceContext traceContext, JwtConfig jwtConfig) {
        this.auth = auth;
        this.traceContext = traceContext;
        this.cookieSecure = jwtConfig.cookieSecure();
    }

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<RegisterResponse>> register(
            @Valid @RequestBody RegisterRequest request) {
        String traceId = traceContext.currentTraceId();
        RegisterResponse result = auth.register(request);
        return ResponseEntity.status(201)
            .header("X-Trace-Id", traceId)
            .body(new ApiResponse<>(result, traceId));
    }

    @PostMapping("/verify")
    public ResponseEntity<ApiResponse<Void>> verify(
            @Valid @RequestBody VerifyEmailRequest request) {
        String traceId = traceContext.currentTraceId();
        auth.verifyEmail(request.email(), request.token());
        return ResponseEntity.ok()
            .header("X-Trace-Id", traceId)
            .body(new ApiResponse<>(null, traceId));
    }

    @PostMapping("/resend-verification")
    public ResponseEntity<ApiResponse<Void>> resendVerification(
            @Valid @RequestBody ResendVerificationRequest request) {
        String traceId = traceContext.currentTraceId();
        auth.resendVerification(request.email());
        return ResponseEntity.ok()
            .header("X-Trace-Id", traceId)
            .body(new ApiResponse<>(null, traceId));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<TokenResponse>> login(
            @Valid @RequestBody LoginRequest request,
            @CookieValue(name = COOKIE_REFRESH, required = false) String replacedRefreshToken,
            HttpServletResponse response) {
        String traceId = traceContext.currentTraceId();
        TokenResponse result = auth.login(request.email(), request.password());
        if (replacedRefreshToken != null) {
            auth.logout(replacedRefreshToken);
        }
        setAuthCookies(response, result);
        return ResponseEntity.ok()
            .header("X-Trace-Id", traceId)
            .body(new ApiResponse<>(result, traceId));
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<TokenResponse>> refresh(
            HttpServletRequest request,
            HttpServletResponse response) {
        String traceId = traceContext.currentTraceId();
        String rawToken = extractRefreshToken(request);
        if (rawToken == null) {
            throw new UnauthorizedException("Refresh token required");
        }
        TokenResponse result = auth.refreshToken(rawToken);
        setAuthCookies(response, result);
        return ResponseEntity.ok()
            .header("X-Trace-Id", traceId)
            .body(new ApiResponse<>(result, traceId));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(
            HttpServletRequest request,
            HttpServletResponse response) {
        String traceId = traceContext.currentTraceId();
        String rawToken = extractRefreshToken(request);
        if (rawToken != null) {
            CompletableFuture.runAsync(() -> auth.logout(rawToken));
        }
        clearAuthCookies(response);
        return ResponseEntity.ok()
            .header("X-Trace-Id", traceId)
            .body(new ApiResponse<>(null, traceId));
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<ApiResponse<Void>> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest request) {
        String traceId = traceContext.currentTraceId();
        auth.forgotPassword(request.email());
        return ResponseEntity.ok()
            .header("X-Trace-Id", traceId)
            .body(new ApiResponse<>(null, traceId));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<ApiResponse<Void>> resetPassword(
            @Valid @RequestBody ResetPasswordRequest request) {
        String traceId = traceContext.currentTraceId();
        auth.resetPassword(request.token(), request.newPassword());
        return ResponseEntity.ok()
            .header("X-Trace-Id", traceId)
            .body(new ApiResponse<>(null, traceId));
    }

    @GetMapping("/users")
    public ResponseEntity<ApiResponse<List<UserSummaryResponse>>> usersByIds(
            @RequestParam List<UUID> ids,
            @AuthenticationPrincipal JwtClaims user) {
        String traceId = traceContext.currentTraceId();
        List<UserSummaryResponse> result = auth.listUsersByIds(user.tenantId(), Set.copyOf(ids)).stream()
            .map(UserSummaryResponse::from)
            .toList();
        return ResponseEntity.ok()
            .header("X-Trace-Id", traceId)
            .body(new ApiResponse<>(result, traceId));
    }

    @GetMapping("/admin/users")
    public ResponseEntity<ApiResponse<List<UserSummaryResponse>>> listUsers(@AuthenticationPrincipal JwtClaims user) {
        String traceId = traceContext.currentTraceId();
        List<UserSummaryResponse> result = auth.listUsers(user.tenantId()).stream()
            .map(UserSummaryResponse::from)
            .toList();
        return ResponseEntity.ok()
            .header("X-Trace-Id", traceId)
            .body(new ApiResponse<>(result, traceId));
    }

    @PostMapping("/admin/invite")
    public ResponseEntity<ApiResponse<InviteUserResponse>> invite(
            @Valid @RequestBody InviteUserRequest request,
            @AuthenticationPrincipal JwtClaims user) {
        String traceId = traceContext.currentTraceId();
        InviteUserResponse result = auth.inviteUser(user.tenantId(), user.actor(), request);
        return ResponseEntity.status(201)
            .header("X-Trace-Id", traceId)
            .body(new ApiResponse<>(result, traceId));
    }

    @PatchMapping("/admin/users/{id}/role")
    public ResponseEntity<ApiResponse<UserSummaryResponse>> updateUserRole(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateUserRoleRequest request,
            @AuthenticationPrincipal JwtClaims user) {
        String traceId = traceContext.currentTraceId();
        UserSummaryResponse result = UserSummaryResponse.from(
            auth.updateUserRole(user.tenantId(), user.actor(), id, request.role()));
        return ResponseEntity.ok()
            .header("X-Trace-Id", traceId)
            .body(new ApiResponse<>(result, traceId));
    }

    @PostMapping("/admin/users/{id}/disable")
    public ResponseEntity<ApiResponse<UserSummaryResponse>> disableUser(@PathVariable UUID id,
            @AuthenticationPrincipal JwtClaims user) {
        String traceId = traceContext.currentTraceId();
        UserSummaryResponse result = UserSummaryResponse.from(
            auth.setUserDisabled(user.tenantId(), user.actor(), id, true));
        return ResponseEntity.ok()
            .header("X-Trace-Id", traceId)
            .body(new ApiResponse<>(result, traceId));
    }

    @PostMapping("/admin/users/{id}/enable")
    public ResponseEntity<ApiResponse<UserSummaryResponse>> enableUser(@PathVariable UUID id,
            @AuthenticationPrincipal JwtClaims user) {
        String traceId = traceContext.currentTraceId();
        UserSummaryResponse result = UserSummaryResponse.from(
            auth.setUserDisabled(user.tenantId(), user.actor(), id, false));
        return ResponseEntity.ok()
            .header("X-Trace-Id", traceId)
            .body(new ApiResponse<>(result, traceId));
    }

    @DeleteMapping("/admin/users/{id}")
    public ResponseEntity<ApiResponse<Void>> removeUser(@PathVariable UUID id,
            @AuthenticationPrincipal JwtClaims user) {
        String traceId = traceContext.currentTraceId();
        auth.removeUser(user.tenantId(), user.actor(), id);
        return ResponseEntity.noContent()
            .header("X-Trace-Id", traceId)
            .build();
    }

    @PostMapping("/accept-invite")
    public ResponseEntity<ApiResponse<TokenResponse>> acceptInvite(
            @Valid @RequestBody AcceptInviteRequest request,
            HttpServletResponse response) {
        String traceId = traceContext.currentTraceId();
        TokenResponse result = auth.acceptInvite(request);
        setAuthCookies(response, result);
        return ResponseEntity.ok()
            .header("X-Trace-Id", traceId)
            .body(new ApiResponse<>(result, traceId));
    }

    @GetMapping("/userinfo")
    public ResponseEntity<ApiResponse<UserInfoResponse>> userinfo(@AuthenticationPrincipal JwtClaims user) {
        String traceId = traceContext.currentTraceId();
        UserInfoResponse info = new UserInfoResponse(
            user.userId(),
            user.email(),
            user.name(),
            user.authProvider(),
            user.tenantId(),
            user.roles()
        );
        return ResponseEntity.ok()
            .header("X-Trace-Id", traceId)
            .body(new ApiResponse<>(info, traceId));
    }

    @PutMapping("/userinfo")
    public ResponseEntity<ApiResponse<UserInfoResponse>> updateUserinfo(
            @Valid @RequestBody UpdateUserInfoRequest request,
            HttpServletResponse response,
            @AuthenticationPrincipal JwtClaims user) {
        String traceId = traceContext.currentTraceId();
        UpdateUserInfoResult result = auth.updateUserInfo(user.userId(), request.name(), request.email());
        response.addHeader(HttpHeaders.SET_COOKIE,
            ResponseCookie.from(COOKIE_JWT, result.accessToken())
                .httpOnly(true).secure(cookieSecure).sameSite("Lax").path("/")
                .maxAge(result.expiresIn()).build().toString());
        return ResponseEntity.ok()
            .header("X-Trace-Id", traceId)
            .body(new ApiResponse<>(result.userInfo(), traceId));
    }

    private void setAuthCookies(HttpServletResponse response, TokenResponse tokens) {
        if (tokens.accessToken() != null) {
            response.addHeader(HttpHeaders.SET_COOKIE,
                ResponseCookie.from(COOKIE_JWT, tokens.accessToken())
                    .httpOnly(true).secure(cookieSecure).sameSite("Lax").path("/")
                    .maxAge(tokens.expiresIn()).build().toString());
        }
        if (tokens.refreshToken() != null) {
            response.addHeader(HttpHeaders.SET_COOKIE,
                ResponseCookie.from(COOKIE_REFRESH, tokens.refreshToken())
                    .httpOnly(true).secure(cookieSecure).sameSite("Lax").path("/")
                    .maxAge(30L * 24 * 3600).build().toString());
        }
    }

    private void clearAuthCookies(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE,
            ResponseCookie.from(COOKIE_JWT, "")
                .httpOnly(true).secure(cookieSecure).path("/").maxAge(0).build().toString());
        response.addHeader(HttpHeaders.SET_COOKIE,
            ResponseCookie.from(COOKIE_REFRESH, "")
                .httpOnly(true).secure(cookieSecure).path("/").maxAge(0).build().toString());
    }

    private String extractRefreshToken(HttpServletRequest request) {
        if (request.getCookies() != null) {
            return Arrays.stream(request.getCookies())
                .filter(c -> COOKIE_REFRESH.equals(c.getName()))
                .map(Cookie::getValue)
                .findFirst()
                .orElse(null);
        }
        String bearer = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (bearer != null && bearer.startsWith("Bearer ")) return bearer.substring(7);
        return null;
    }
}
