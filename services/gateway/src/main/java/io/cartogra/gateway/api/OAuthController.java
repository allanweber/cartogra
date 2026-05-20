package io.cartogra.gateway.api;

import io.cartogra.gateway.api.dto.ApiResponse;
import io.cartogra.gateway.api.dto.TokenResponse;
import io.cartogra.gateway.application.OAuthCallbackUseCase;
import io.cartogra.gateway.application.OAuthStartUseCase;
import io.cartogra.gateway.infrastructure.tracing.TraceContext;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
    public ResponseEntity<Void> start(
            @PathVariable String provider,
            @RequestParam(required = false) UUID tenantId) {
        String state = UUID.randomUUID().toString().replace("-", "");
        String redirectUri = oauthStart.buildAuthorizationUri(provider, tenantId, state);
        return ResponseEntity.status(HttpStatus.FOUND)
            .header(HttpHeaders.LOCATION, redirectUri)
            .build();
    }

    @GetMapping("/{provider}/callback")
    public ResponseEntity<ApiResponse<TokenResponse>> callback(
            @PathVariable String provider,
            @RequestParam String code,
            @RequestParam String state,
            HttpServletResponse response) {
        String traceId = traceContext.currentTraceId();
        TokenResponse result = oauthCallback.execute(provider, code, state);
        if (result.accessToken() != null) {
            response.addHeader(HttpHeaders.SET_COOKIE,
                ResponseCookie.from(COOKIE_JWT, result.accessToken())
                    .httpOnly(true).secure(true).sameSite("Lax").path("/")
                    .maxAge(result.expiresIn()).build().toString());
        }
        if (result.refreshToken() != null) {
            response.addHeader(HttpHeaders.SET_COOKIE,
                ResponseCookie.from(COOKIE_REFRESH, result.refreshToken())
                    .httpOnly(true).secure(true).sameSite("Lax").path("/v1/auth/refresh")
                    .maxAge(30L * 24 * 3600).build().toString());
        }
        return ResponseEntity.ok()
            .header("X-Trace-Id", traceId)
            .body(new ApiResponse<>(result, traceId));
    }
}
