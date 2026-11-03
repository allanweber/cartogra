package io.cartogra.gateway.config;

import io.cartogra.common.identity.Roles;
import io.cartogra.gateway.infrastructure.security.JsonAccessDeniedHandler;
import io.cartogra.gateway.infrastructure.security.JwtAuthentication;
import io.cartogra.gateway.infrastructure.tracing.TraceContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Primary guest guard (ADR-0019): a GUEST token may only read. Keyed on HTTP method, not on routes, so an
 * endpoint added later is denied by default.
 */
@Component
public class GuestWriteDenyFilter extends OncePerRequestFilter {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private static final Set<String> ALLOWED_WRITES = Set.of("/api/auth/guest", "/api/auth/logout");

    private final JsonAccessDeniedHandler accessDenied;

    public GuestWriteDenyFilter(TraceContext traceContext) {
        this.accessDenied = new JsonAccessDeniedHandler(traceContext);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (isGuest() && !SAFE_METHODS.contains(request.getMethod())
                && !ALLOWED_WRITES.contains(request.getRequestURI())) {
            accessDenied.handle(request, response, new AccessDeniedException("Guest access is read-only"));
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static boolean isGuest() {
        return SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthentication auth
            && auth.getClaims().roles().contains(Roles.GUEST);
    }
}
