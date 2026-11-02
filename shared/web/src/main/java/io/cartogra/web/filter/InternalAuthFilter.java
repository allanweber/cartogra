package io.cartogra.web.filter;

import io.cartogra.common.identity.Actor;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Seeds the {@code SecurityContext} from the {@code X-User-Roles} / {@code X-User-Id} headers the
 * Gateway attaches to every proxied request. Not a {@code @Component}: cross-module component
 * scanning isn't reliable across service boundaries (each service's {@code @SpringBootApplication}
 * only scans its own package tree), so consumers declare it as a {@code @Bean} explicitly and wire
 * it into their own {@code SecurityFilterChain} with {@code addFilterBefore}.
 */
public class InternalAuthFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String rolesHeader = request.getHeader("X-User-Roles");
        if (rolesHeader != null && !rolesHeader.isBlank()) {
            List<SimpleGrantedAuthority> authorities = Arrays.stream(rolesHeader.split(","))
                .map(String::trim)
                .filter(r -> !r.isEmpty())
                .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                .toList();
            boolean admin = authorities.stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
            var actor = new Actor(parseUserId(request.getHeader("X-User-Id")), admin);
            var auth = new UsernamePasswordAuthenticationToken(actor, null, authorities);
            SecurityContextHolder.getContext().setAuthentication(auth);
        }
        filterChain.doFilter(request, response);
    }

    private static UUID parseUserId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException _) {
            return null;
        }
    }
}
