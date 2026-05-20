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
import java.util.stream.Collectors;

@Component
public class ProxyRequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ProxyRequestLoggingFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (log.isDebugEnabled()) {
            String headers = Collections.list(request.getHeaderNames()).stream()
                    .collect(Collectors.toMap(h -> h, request::getHeader))
                    .toString();
            log.debug("Proxying {} {} | headers={}", request.getMethod(), request.getRequestURI(), headers);
        } else {
            log.info("Proxying {} {} | X-Tenant-Id={} X-User-Id={}",
                    request.getMethod(),
                    request.getRequestURI(),
                    request.getHeader("X-Tenant-Id"),
                    request.getHeader("X-User-Id"));
        }
        filterChain.doFilter(request, response);
    }
}
