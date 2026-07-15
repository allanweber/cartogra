package io.cartogra.gateway.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class ProxyRequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ProxyRequestLoggingFilter.class);

    private static final Set<String> SENSITIVE_HEADERS = Set.of("authorization", "cookie", "set-cookie");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (log.isDebugEnabled()) {
            String headers = Collections.list(request.getHeaderNames()).stream()
                    .collect(Collectors.toMap(
                            h -> h,
                            h -> SENSITIVE_HEADERS.contains(h.toLowerCase(Locale.ROOT))
                                    ? "[REDACTED]"
                                    : request.getHeader(h)))
                    .toString();
            log.debug("Proxying {} {} | headers={}", request.getMethod(), request.getRequestURI(), headers);
        } else {
            log.info("Proxying {} {} | X-Tenant-Id={} X-User-Id={} X-User-Roles={}",
                    request.getMethod(),
                    request.getRequestURI(),
                    request.getHeader("X-Tenant-Id"),
                    request.getHeader("X-User-Id"),
                    request.getHeader("X-User-Roles"));
        }
        filterChain.doFilter(request, response);
    }
}
