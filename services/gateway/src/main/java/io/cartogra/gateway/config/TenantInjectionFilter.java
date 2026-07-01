package io.cartogra.gateway.config;

import io.cartogra.gateway.infrastructure.security.JwtAuthentication;
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
import java.util.List;
import java.util.Map;

@Component
public class TenantInjectionFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthentication jwtAuth) {
            String roles = String.join(",", jwtAuth.getClaims().roles());
            filterChain.doFilter(new TenantInjectingRequestWrapper(request,
                jwtAuth.getTenantId().toString(), jwtAuth.getUserId().toString(), roles), response);
        } else {
            filterChain.doFilter(new TenantStrippingRequestWrapper(request), response);
        }
    }

    private static class TenantInjectingRequestWrapper extends HttpServletRequestWrapper {
        private static final List<String> MANAGED = List.of("X-Tenant-Id", "X-User-Id", "X-User-Roles");
        private final Map<String, String> extraHeaders;

        TenantInjectingRequestWrapper(HttpServletRequest request, String tenantId, String userId, String roles) {
            super(request);
            extraHeaders = Map.of("X-Tenant-Id", tenantId, "X-User-Id", userId, "X-User-Roles", roles);
        }

        @Override
        public String getHeader(String name) {
            String key = normaliseKey(name);
            if (key != null) return extraHeaders.get(key);
            return super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            String key = normaliseKey(name);
            if (key != null) {
                String val = extraHeaders.get(key);
                return val != null ? Collections.enumeration(List.of(val)) : Collections.emptyEnumeration();
            }
            return super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            Map<String, String> all = new HashMap<>(extraHeaders);
            Enumeration<String> orig = super.getHeaderNames();
            while (orig.hasMoreElements()) {
                String h = orig.nextElement();
                if (MANAGED.stream().noneMatch(m -> m.equalsIgnoreCase(h))) {
                    all.put(h, h);
                }
            }
            return Collections.enumeration(all.keySet());
        }

        private String normaliseKey(String name) {
            return MANAGED.stream().filter(m -> m.equalsIgnoreCase(name)).findFirst().orElse(null);
        }
    }

    private static class TenantStrippingRequestWrapper extends HttpServletRequestWrapper {
        private static final List<String> STRIP = List.of("X-Tenant-Id", "X-User-Id", "X-User-Roles");

        TenantStrippingRequestWrapper(HttpServletRequest request) {
            super(request);
        }

        @Override
        public String getHeader(String name) {
            if (STRIP.stream().anyMatch(h -> h.equalsIgnoreCase(name))) return null;
            return super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            if (STRIP.stream().anyMatch(h -> h.equalsIgnoreCase(name)))
                return Collections.emptyEnumeration();
            return super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            return Collections.enumeration(
                Collections.list(super.getHeaderNames()).stream()
                    .filter(h -> STRIP.stream().noneMatch(s -> s.equalsIgnoreCase(h)))
                    .toList());
        }
    }
}
