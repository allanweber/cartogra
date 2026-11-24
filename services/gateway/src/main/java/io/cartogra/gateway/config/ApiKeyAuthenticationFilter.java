package io.cartogra.gateway.config;

import io.cartogra.gateway.domain.ApiKey;
import io.cartogra.gateway.domain.ApiKeyAuthenticator;
import io.cartogra.gateway.infrastructure.security.ApiKeyAuthentication;
import io.cartogra.gateway.infrastructure.security.JsonAccessDeniedHandler;
import io.cartogra.gateway.infrastructure.security.JsonAuthenticationEntryPoint;
import io.cartogra.gateway.infrastructure.security.JwtAuthentication;
import io.cartogra.gateway.infrastructure.tracing.TraceContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * Authenticates a request with an API key, only on the endpoints of the {@link ApiKeyAllowlist}. It runs after
 * {@link JwtAuthenticationFilter}. On an allowlisted endpoint only a key authenticates: a JWT is ignored when
 * a key is present, and is refused (403) when there is no key. Off the allowlist the filter does nothing.
 * On the allowlist the request is first charged to an IP bucket, before the key lookup.
 */
@Component
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private final ApiKeyAuthenticator authenticator;
    private final JsonAuthenticationEntryPoint entryPoint;
    private final JsonAccessDeniedHandler accessDenied;
    private final RateLimiter rateLimit;

    public ApiKeyAuthenticationFilter(ApiKeyAuthenticator authenticator, TraceContext traceContext,
            RateLimiter rateLimit) {
        this.authenticator = authenticator;
        this.rateLimit = rateLimit;
        this.entryPoint = new JsonAuthenticationEntryPoint(traceContext);
        this.accessDenied = new JsonAccessDeniedHandler(traceContext);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        Optional<String> requiredScope = ApiKeyAllowlist.requiredScope(request.getMethod(), request.getRequestURI());
        if (requiredScope.isEmpty()) {
            filterChain.doFilter(request, response);
            return;
        }
        if (!rateLimit.allowBeforeKeyLookup(request, response)) {
            SecurityContextHolder.clearContext();
            return;
        }
        String rawKey = request.getHeader(ApiKeyAllowlist.HEADER);
        boolean hasKey = rawKey != null && !rawKey.isBlank();
        boolean hasJwt = SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthentication;
        SecurityContextHolder.clearContext();

        if (!hasKey) {
            if (hasJwt) {
                accessDenied.handle(request, response, new AccessDeniedException("An API key is required"));
            } else {
                entryPoint.commence(request, response, new BadCredentialsException("API key missing"));
            }
            return;
        }
        Optional<ApiKey> key = authenticator.authenticate(rawKey.strip());
        if (key.isEmpty()) {
            entryPoint.commence(request, response, new BadCredentialsException("API key not valid"));
            return;
        }
        var auth = new ApiKeyAuthentication(key.get());
        if (!auth.hasScope(requiredScope.get())) {
            accessDenied.handle(request, response, new AccessDeniedException("API key scope missing"));
            return;
        }
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        filterChain.doFilter(request, response);
    }
}
