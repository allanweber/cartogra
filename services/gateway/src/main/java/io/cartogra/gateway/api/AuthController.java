package io.cartogra.gateway.api;

import io.cartogra.gateway.api.dto.*;
import io.cartogra.gateway.application.*;
import io.cartogra.gateway.domain.exception.UnauthorizedException;
import io.cartogra.gateway.infrastructure.security.JwtAuthentication;
import io.cartogra.gateway.infrastructure.tracing.TraceContext;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/v1/auth")
public class AuthController {

    private static final String COOKIE_JWT = "jwt";
    private static final String COOKIE_REFRESH = "jwt_refresh";

    private final RegisterUserUseCase registerUser;
    private final VerifyEmailUseCase verifyEmail;
    private final LoginUseCase login;
    private final RefreshTokenUseCase refreshToken;
    private final LogoutUseCase logout;
    private final TraceContext traceContext;

    public AuthController(RegisterUserUseCase registerUser,
                          VerifyEmailUseCase verifyEmail,
                          LoginUseCase login,
                          RefreshTokenUseCase refreshToken,
                          LogoutUseCase logout,
                          TraceContext traceContext) {
        this.registerUser = registerUser;
        this.verifyEmail = verifyEmail;
        this.login = login;
        this.refreshToken = refreshToken;
        this.logout = logout;
        this.traceContext = traceContext;
    }

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<RegisterResponse>> register(
            @Valid @RequestBody RegisterRequest request) {
        String traceId = traceContext.currentTraceId();
        RegisterResponse result = registerUser.execute(request);
        return ResponseEntity.status(201)
            .header("X-Trace-Id", traceId)
            .body(new ApiResponse<>(result, traceId));
    }

    @PostMapping("/verify")
    public ResponseEntity<ApiResponse<Void>> verify(
            @Valid @RequestBody VerifyEmailRequest request) {
        String traceId = traceContext.currentTraceId();
        verifyEmail.execute(request.email(), request.token());
        return ResponseEntity.ok()
            .header("X-Trace-Id", traceId)
            .body(new ApiResponse<>(null, traceId));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<TokenResponse>> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletResponse response) {
        String traceId = traceContext.currentTraceId();
        UUID tenantId = request.tenantId() != null ? request.tenantId() : UUID.randomUUID();
        TokenResponse result = login.execute(tenantId, request.email(), request.password());
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
        TokenResponse result = refreshToken.execute(rawToken);
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
            CompletableFuture.runAsync(() -> logout.execute(rawToken));
        }
        clearAuthCookies(response);
        return ResponseEntity.ok()
            .header("X-Trace-Id", traceId)
            .body(new ApiResponse<>(null, traceId));
    }

    @GetMapping("/userinfo")
    public ResponseEntity<ApiResponse<UserInfoResponse>> userinfo(
            @AuthenticationPrincipal JwtAuthentication principal) {
        if (principal == null) {
            throw new UnauthorizedException("Authentication required");
        }
        String traceId = traceContext.currentTraceId();
        UserInfoResponse info = new UserInfoResponse(
            principal.getClaims().userId(),
            principal.getClaims().email(),
            principal.getClaims().tenantId(),
            principal.getClaims().roles()
        );
        return ResponseEntity.ok()
            .header("X-Trace-Id", traceId)
            .body(new ApiResponse<>(info, traceId));
    }

    private void setAuthCookies(HttpServletResponse response, TokenResponse tokens) {
        if (tokens.accessToken() != null) {
            response.addHeader(HttpHeaders.SET_COOKIE,
                ResponseCookie.from(COOKIE_JWT, tokens.accessToken())
                    .httpOnly(true).secure(true).sameSite("Lax").path("/")
                    .maxAge(tokens.expiresIn()).build().toString());
        }
        if (tokens.refreshToken() != null) {
            response.addHeader(HttpHeaders.SET_COOKIE,
                ResponseCookie.from(COOKIE_REFRESH, tokens.refreshToken())
                    .httpOnly(true).secure(true).sameSite("Lax").path("/v1/auth/refresh")
                    .maxAge(30L * 24 * 3600).build().toString());
        }
    }

    private void clearAuthCookies(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE,
            ResponseCookie.from(COOKIE_JWT, "")
                .httpOnly(true).secure(true).path("/").maxAge(0).build().toString());
        response.addHeader(HttpHeaders.SET_COOKIE,
            ResponseCookie.from(COOKIE_REFRESH, "")
                .httpOnly(true).secure(true).path("/v1/auth/refresh").maxAge(0).build().toString());
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
