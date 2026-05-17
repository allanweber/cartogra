package io.cartogra.gateway.api;

import io.cartogra.gateway.api.dto.*;
import io.cartogra.gateway.application.*;
import io.cartogra.gateway.domain.exception.UnauthorizedException;
import io.cartogra.gateway.infrastructure.security.JwtAuthentication;
import io.cartogra.gateway.infrastructure.tracing.TraceContext;
import jakarta.validation.Valid;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.UUID;

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
    public Mono<ResponseEntity<ApiResponse<RegisterResponse>>> register(
            @Valid @RequestBody RegisterRequest request) {
        return Mono.deferContextual(ctx -> {
            String traceId = traceContext.currentTraceId(ctx);
            return Mono.fromCallable(() -> registerUser.execute(request))
                .subscribeOn(Schedulers.boundedElastic())
                .map(result -> ResponseEntity.status(201)
                    .header("X-Trace-Id", traceId)
                    .<ApiResponse<RegisterResponse>>body(new ApiResponse<>(result, traceId)));
        });
    }

    @PostMapping("/verify")
    public Mono<ResponseEntity<ApiResponse<Void>>> verify(
            @Valid @RequestBody VerifyEmailRequest request) {
        return Mono.deferContextual(ctx -> {
            String traceId = traceContext.currentTraceId(ctx);
            return Mono.fromRunnable(() -> verifyEmail.execute(request.email(), request.token()))
                .subscribeOn(Schedulers.boundedElastic())
                .thenReturn(ResponseEntity.ok()
                    .header("X-Trace-Id", traceId)
                    .<ApiResponse<Void>>body(new ApiResponse<>(null, traceId)));
        });
    }

    @PostMapping("/login")
    public Mono<ResponseEntity<ApiResponse<TokenResponse>>> login(
            @Valid @RequestBody LoginRequest request,
            ServerHttpResponse response) {
        UUID tenantId = request.tenantId() != null ? request.tenantId() : UUID.randomUUID();
        return Mono.deferContextual(ctx -> {
            String traceId = traceContext.currentTraceId(ctx);
            return Mono.fromCallable(() -> login.execute(tenantId, request.email(), request.password()))
                .subscribeOn(Schedulers.boundedElastic())
                .map(result -> {
                    setAuthCookies(response, result);
                    return ResponseEntity.ok()
                        .header("X-Trace-Id", traceId)
                        .<ApiResponse<TokenResponse>>body(new ApiResponse<>(result, traceId));
                });
        });
    }

    @PostMapping("/refresh")
    public Mono<ResponseEntity<ApiResponse<TokenResponse>>> refresh(
            ServerHttpRequest request,
            ServerHttpResponse response) {
        String rawToken = extractRefreshToken(request);
        return Mono.deferContextual(ctx -> {
            String traceId = traceContext.currentTraceId(ctx);
            if (rawToken == null) {
                return Mono.just(ResponseEntity.status(401)
                    .header("X-Trace-Id", traceId)
                    .<ApiResponse<TokenResponse>>body(new ApiResponse<>(null, traceId)));
            }
            return Mono.fromCallable(() -> refreshToken.execute(rawToken))
                .subscribeOn(Schedulers.boundedElastic())
                .map(result -> {
                    setAuthCookies(response, result);
                    return ResponseEntity.ok()
                        .header("X-Trace-Id", traceId)
                        .<ApiResponse<TokenResponse>>body(new ApiResponse<>(result, traceId));
                });
        });
    }

    @PostMapping("/logout")
    public Mono<ResponseEntity<ApiResponse<Void>>> logout(
            ServerHttpRequest request,
            ServerHttpResponse response) {
        String rawToken = extractRefreshToken(request);
        return Mono.deferContextual(ctx -> {
            String traceId = traceContext.currentTraceId(ctx);
            return Mono.fromRunnable(() -> {
                    if (rawToken != null) logout.execute(rawToken);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .thenReturn(ResponseEntity.ok()
                    .header("X-Trace-Id", traceId)
                    .<ApiResponse<Void>>body(new ApiResponse<>(null, traceId)))
                .doOnNext(_ -> clearAuthCookies(response));
        });
    }

    @GetMapping("/userinfo")
    public Mono<ResponseEntity<ApiResponse<UserInfoResponse>>> userinfo(
            @AuthenticationPrincipal JwtAuthentication principal) {
        if (principal == null) {
            return Mono.error(new UnauthorizedException("Authentication required"));
        }
        return Mono.deferContextual(ctx -> {
            String traceId = traceContext.currentTraceId(ctx);
            UserInfoResponse info = new UserInfoResponse(
                principal.getClaims().userId(),
                principal.getClaims().email(),
                principal.getClaims().tenantId(),
                principal.getClaims().roles()
            );
            return Mono.just(ResponseEntity.ok()
                .<ApiResponse<UserInfoResponse>>body(new ApiResponse<>(info, traceId)));
        });
    }

    private void setAuthCookies(ServerHttpResponse response, TokenResponse tokens) {
        if (tokens.accessToken() != null) {
            response.addCookie(ResponseCookie.from(COOKIE_JWT, tokens.accessToken())
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(tokens.expiresIn())
                .build());
        }
        if (tokens.refreshToken() != null) {
            response.addCookie(ResponseCookie.from(COOKIE_REFRESH, tokens.refreshToken())
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path("/v1/auth/refresh")
                .maxAge(30L * 24 * 3600)
                .build());
        }
    }

    private void clearAuthCookies(ServerHttpResponse response) {
        response.addCookie(ResponseCookie.from(COOKIE_JWT, "")
            .httpOnly(true).secure(true).path("/").maxAge(0).build());
        response.addCookie(ResponseCookie.from(COOKIE_REFRESH, "")
            .httpOnly(true).secure(true).path("/v1/auth/refresh").maxAge(0).build());
    }

    private String extractRefreshToken(ServerHttpRequest request) {
        HttpCookie cookie = request.getCookies().getFirst(COOKIE_REFRESH);
        if (cookie != null) return cookie.getValue();
        String bearer = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (bearer != null && bearer.startsWith("Bearer ")) return bearer.substring(7);
        return null;
    }
}
