package io.cartogra.gateway.api;

import io.cartogra.gateway.api.dto.TokenResponse;
import io.cartogra.gateway.domain.OAuthService;
import io.cartogra.gateway.config.FrontendProperties;
import io.cartogra.gateway.infrastructure.tracing.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/auth/oauth")
public class OAuthController {

    private static final Logger log = LoggerFactory.getLogger(OAuthController.class);
    private static final String COOKIE_JWT = "jwt";
    private static final String COOKIE_REFRESH = "jwt_refresh";

    private final OAuthService oauth;
    private final FrontendProperties frontend;
    private final TraceContext traceContext;

    public OAuthController(OAuthService oauth, FrontendProperties frontend, TraceContext traceContext) {
        this.oauth = oauth;
        this.frontend = frontend;
        this.traceContext = traceContext;
    }

    @GetMapping("/{provider}/start")
    public ResponseEntity<Void> start(
            @PathVariable String provider,
            @RequestParam(required = false) UUID tenantId) {
        String state = UUID.randomUUID().toString().replace("-", "");
        String redirectUri = oauth.buildAuthorizationUri(provider, tenantId, state);
        return ResponseEntity.status(HttpStatus.FOUND)
            .header(HttpHeaders.LOCATION, redirectUri)
            .build();
    }

    @GetMapping("/{provider}/callback")
    public ResponseEntity<Void> callback(
            @PathVariable String provider,
            @RequestParam String code,
            @RequestParam String state) {
        String traceId = traceContext.currentTraceId();
        try {
            TokenResponse result = oauth.handleCallback(provider, code, state);
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.LOCATION, frontend.baseUrl() + "/dashboard");
            headers.add("X-Trace-Id", traceId);
            if (result.accessToken() != null) {
                headers.add(HttpHeaders.SET_COOKIE,
                    ResponseCookie.from(COOKIE_JWT, result.accessToken())
                        .httpOnly(true).secure(false).sameSite("Lax").path("/")
                        .maxAge(result.expiresIn()).build().toString());
            }
            if (result.refreshToken() != null) {
                headers.add(HttpHeaders.SET_COOKIE,
                    ResponseCookie.from(COOKIE_REFRESH, result.refreshToken())
                        .httpOnly(true).secure(false).sameSite("Lax").path("/api/auth/refresh")
                        .maxAge(30L * 24 * 3600).build().toString());
            }
            return ResponseEntity.status(HttpStatus.FOUND).headers(headers).build();
        } catch (Exception e) {
            log.warn("OAuth callback failed for provider {}: {}", provider, e.getMessage());
            return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, frontend.baseUrl() + "/login?error=oauth_failed")
                .header("X-Trace-Id", traceId)
                .build();
        }
    }
}
