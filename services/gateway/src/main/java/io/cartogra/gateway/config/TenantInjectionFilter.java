package io.cartogra.gateway.config;

import io.cartogra.gateway.infrastructure.security.JwtAuthentication;
import io.cartogra.web.security.InternalClaims;
import io.cartogra.web.security.InternalTokenCodec;
import io.cartogra.web.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Removes the identity headers from each inbound request, then sets them again from the validated JWT. For a
 * request to a proxied service, it also adds an {@code X-Internal-Token} signed for that service, which is the
 * only identity the services trust. For an authenticated request, it binds {@link TenantContext} to the JWT
 * tenant for the rest of the chain (RLS).
 */
@Component
public class TenantInjectionFilter extends OncePerRequestFilter {

    private static final List<String> MANAGED =
            List.of("X-Tenant-Id", "X-User-Id", "X-User-Roles", InternalTokenCodec.HEADER);

    private static final Map<String, String> AUDIENCE_BY_PREFIX = Map.of(
            "/api/v1/registry", "registry",
            "/api/v1/topology", "topology",
            "/api/v1/contracts", "contract",
            "/api/v1/ingestion", "ingestion");

    private final InternalTokenCodec internalTokenCodec;

    public TenantInjectionFilter(InternalTokenCodec internalTokenCodec) {
        this.internalTokenCodec = internalTokenCodec;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        Map<String, String> headers = new HashMap<>();
        if (!(SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthentication jwtAuth)) {
            filterChain.doFilter(new ManagedHeadersRequestWrapper(request, headers), response);
            return;
        }
        List<String> roles = jwtAuth.getClaims().roles();
        headers.put("X-Tenant-Id", jwtAuth.getTenantId().toString());
        headers.put("X-User-Id", jwtAuth.getUserId().toString());
        headers.put("X-User-Roles", String.join(",", roles));
        audienceOf(request.getRequestURI()).ifPresent(audience -> headers.put(InternalTokenCodec.HEADER,
                internalTokenCodec.sign(new InternalClaims(jwtAuth.getTenantId(),
                        jwtAuth.getUserId().toString(), roles), audience)));
        var wrapped = new ManagedHeadersRequestWrapper(request, headers);
        try {
            TenantContext.callAs(jwtAuth.getTenantId(), () -> {
                filterChain.doFilter(wrapped, response);
                return null;
            });
        } catch (IOException | ServletException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new ServletException(e);
        }
    }

    public static Optional<String> audienceOf(String path) {
        return AUDIENCE_BY_PREFIX.entrySet().stream()
                .filter(e -> path.equals(e.getKey()) || path.startsWith(e.getKey() + "/"))
                .map(Map.Entry::getValue)
                .findFirst();
    }

    private static final class ManagedHeadersRequestWrapper extends HttpServletRequestWrapper {

        private final Map<String, String> managed;

        ManagedHeadersRequestWrapper(HttpServletRequest request, Map<String, String> managed) {
            super(request);
            this.managed = managed;
        }

        @Override
        public String getHeader(String name) {
            Optional<String> key = managedKey(name);
            return key.isPresent() ? managed.get(key.get()) : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            Optional<String> key = managedKey(name);
            if (key.isEmpty()) {
                return super.getHeaders(name);
            }
            String value = managed.get(key.get());
            return value != null ? Collections.enumeration(List.of(value)) : Collections.emptyEnumeration();
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            Set<String> names = new LinkedHashSet<>(managed.keySet());
            Collections.list(super.getHeaderNames()).stream()
                    .filter(h -> managedKey(h).isEmpty())
                    .forEach(names::add);
            return Collections.enumeration(names);
        }

        private static Optional<String> managedKey(String name) {
            return MANAGED.stream().filter(m -> m.equalsIgnoreCase(name)).findFirst();
        }
    }
}
