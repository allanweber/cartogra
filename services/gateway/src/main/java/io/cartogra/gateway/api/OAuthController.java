package io.cartogra.gateway.api;

import io.cartogra.gateway.api.dto.ApiResponse;
import io.cartogra.gateway.api.dto.TokenResponse;
import io.cartogra.gateway.application.OAuthCallbackUseCase;
import io.cartogra.gateway.application.OAuthStartUseCase;
import io.cartogra.gateway.infrastructure.tracing.TraceContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.UUID;

@RestController
@RequestMapping("/v1/auth/oauth")
public class OAuthController {

    private static final String COOKIE_JWT = "jwt";
    private static final String COOKIE_REFRESH = "jwt_refresh";

    private final OAuthStartUseCase oauthStart;
    private final OAuthCallbackUseCase oauthCallback;
    private final TraceContext traceContext;

    public OAuthController(OAuthStartUseCase oauthStart, OAuthCallbackUseCase oauthCallback, TraceContext traceContext) {
        this.oauthStart = oauthStart;
        this.oauthCallback = oauthCallback;
        this.traceContext = traceContext;
    }

    @GetMapping("/{provider}/start")
    public Mono<ResponseEntity<Void>> start(
            @PathVariable String provider,
            @RequestParam(required = false) UUID tenantId) {
        String state = UUID.randomUUID().toString().replace("-", "");
        return Mono.fromCallable(() -> oauthStart.buildAuthorizationUri(provider, tenantId, state))
            .subscribeOn(Schedulers.boundedElastic())
            .map(redirectUri -> ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, redirectUri)
                .<Void>build());
    }

    @GetMapping("/{provider}/callback")
    public Mono<ResponseEntity<ApiResponse<TokenResponse>>> callback(
            @PathVariable String provider,
            @RequestParam String code,
            @RequestParam String state,
            ServerHttpResponse response) {
        return Mono.deferContextual(ctx -> {
            String traceId = traceContext.currentTraceId(ctx);
            return Mono.fromCallable(() -> oauthCallback.execute(provider, code, state))
                .subscribeOn(Schedulers.boundedElastic())
                .map(result -> {
                    if (result.accessToken() != null) {
                        response.addCookie(ResponseCookie.from(COOKIE_JWT, result.accessToken())
                            .httpOnly(true).secure(true).sameSite("Lax").path("/")
                            .maxAge(result.expiresIn()).build());
                    }
                    if (result.refreshToken() != null) {
                        response.addCookie(ResponseCookie.from(COOKIE_REFRESH, result.refreshToken())
                            .httpOnly(true).secure(true).sameSite("Lax").path("/v1/auth/refresh")
                            .maxAge(30L * 24 * 3600).build());
                    }
                    return ResponseEntity.ok()
                        .header("X-Trace-Id", traceId)
                        .<ApiResponse<TokenResponse>>body(new ApiResponse<>(result, traceId));
                });
        });
    }
}
