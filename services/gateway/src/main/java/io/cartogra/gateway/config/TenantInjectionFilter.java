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
            filterChain.doFilter(new TenantInjectingRequestWrapper(request,
                jwtAuth.getTenantId().toString(), jwtAuth.getUserId().toString()), response);
        } else {
            filterChain.doFilter(new TenantStrippingRequestWrapper(request), response);
        }
    }

    private static class TenantInjectingRequestWrapper extends HttpServletRequestWrapper {
        private final Map<String, String> extraHeaders;

        TenantInjectingRequestWrapper(HttpServletRequest request, String tenantId, String userId) {
            super(request);
            extraHeaders = Map.of("X-Tenant-Id", tenantId, "X-User-Id", userId);
        }

        @Override
        public String getHeader(String name) {
            if ("X-Tenant-Id".equalsIgnoreCase(name) || "X-User-Id".equalsIgnoreCase(name)) {
                return extraHeaders.get(normaliseKey(name));
            }
            return super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            if ("X-Tenant-Id".equalsIgnoreCase(name) || "X-User-Id".equalsIgnoreCase(name)) {
                String val = extraHeaders.get(normaliseKey(name));
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
                if (!"X-Tenant-Id".equalsIgnoreCase(h) && !"X-User-Id".equalsIgnoreCase(h)) {
                    all.put(h, h);
                }
            }
            return Collections.enumeration(all.keySet());
        }

        private String normaliseKey(String name) {
            if ("X-Tenant-Id".equalsIgnoreCase(name)) return "X-Tenant-Id";
            if ("X-User-Id".equalsIgnoreCase(name)) return "X-User-Id";
            return name;
        }
    }

    private static class TenantStrippingRequestWrapper extends HttpServletRequestWrapper {
        TenantStrippingRequestWrapper(HttpServletRequest request) {
            super(request);
        }

        @Override
        public String getHeader(String name) {
            if ("X-Tenant-Id".equalsIgnoreCase(name) || "X-User-Id".equalsIgnoreCase(name)) return null;
            return super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            if ("X-Tenant-Id".equalsIgnoreCase(name) || "X-User-Id".equalsIgnoreCase(name))
                return Collections.emptyEnumeration();
            return super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            return Collections.enumeration(
                Collections.list(super.getHeaderNames()).stream()
                    .filter(h -> !"X-Tenant-Id".equalsIgnoreCase(h) && !"X-User-Id".equalsIgnoreCase(h))
                    .toList());
        }
    }
}
