package io.cartogra.web.filter;

import io.cartogra.common.identity.Actor;
import io.cartogra.web.security.InternalClaims;
import io.cartogra.web.security.InternalTokenCodec;
import io.cartogra.web.security.JsonUnauthorizedEntryPoint;
import io.cartogra.web.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Seeds the {@code SecurityContext} from the signed {@code X-Internal-Token} only; plain identity headers are
 * never trusted. A present but invalid token, or an {@code X-Tenant-Id} that differs from the token tenant,
 * gets 401 at once. A request with no token continues unauthenticated, so the authorization rules decide.
 * A valid token binds {@link TenantContext} to the token tenant for the rest of the chain (RLS).
 * Not a {@code @Component}: each service declares it as a {@code @Bean} with its own audience.
 */
public class InternalTokenFilter extends OncePerRequestFilter {

    private static final String API_KEY_PREFIX = "apikey:";

    private final InternalTokenCodec codec;
    private final String audience;

    public InternalTokenFilter(InternalTokenCodec codec, String audience) {
        this.codec = codec;
        this.audience = audience;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String token = request.getHeader(InternalTokenCodec.HEADER);
        if (token == null || token.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }
        Optional<InternalClaims> claims = codec.verify(token.trim(), audience);
        if (claims.isEmpty() || !tenantHeaderMatches(request, claims.get().tenantId())) {
            JsonUnauthorizedEntryPoint.write(response);
            return;
        }
        authenticate(claims.get());
        try {
            TenantContext.callAs(claims.get().tenantId(), () -> {
                filterChain.doFilter(request, response);
                return null;
            });
        } catch (IOException | ServletException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new ServletException(e);
        }
    }

    private static boolean tenantHeaderMatches(HttpServletRequest request, UUID tenantId) {
        String header = request.getHeader("X-Tenant-Id");
        return header == null || header.trim().equalsIgnoreCase(tenantId.toString());
    }

    private static void authenticate(InternalClaims claims) {
        List<SimpleGrantedAuthority> authorities = claims.roles().stream()
                .map(String::trim)
                .filter(r -> !r.isEmpty())
                .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                .toList();
        boolean admin = authorities.stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        Actor actor = toActor(claims.subject(), admin);
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(actor, null, authorities));
    }

    private static Actor toActor(String subject, boolean admin) {
        if (subject != null && subject.startsWith(API_KEY_PREFIX)) {
            UUID keyId = parseUuid(subject.substring(API_KEY_PREFIX.length()));
            return keyId == null ? Actor.ANONYMOUS : Actor.apiKey(keyId);
        }
        return new Actor(parseUuid(subject), admin);
    }

    private static UUID parseUuid(String subject) {
        try {
            return UUID.fromString(subject);
        } catch (IllegalArgumentException _) {
            return null;
        }
    }
}
