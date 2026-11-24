package io.cartogra.gateway.config;

import io.cartogra.gateway.domain.GuestService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.cartogra.common.identity.Roles;
import io.cartogra.gateway.infrastructure.security.ApiKeyAuthentication;
import io.cartogra.gateway.infrastructure.security.JwtAuthentication;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private static final String GUEST_MINT_PATH = "/api/auth/guest";

    private final RateLimitProperties props;
    private final GuestProperties guestProps;
    private final RateLimiter rateLimiter;
    private final Counter guestMintsRejected;

    public RateLimitFilter(RateLimitProperties props,
                           GuestProperties guestProps,
                           RateLimiter rateLimiter,
                           MeterRegistry meterRegistry) {
        this.props = props;
        this.guestProps = guestProps;
        this.rateLimiter = rateLimiter;
        this.guestMintsRejected = meterRegistry.counter(GuestService.MINTS_METRIC, "outcome", "rate_limited");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        log.debug("RateLimitFilter invoked: enabled={} path={}", props.enabled(), path);

        if (!props.enabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        boolean isAuthPath = path.startsWith("/api/auth/");
        String bucketScope = resolveBucketScope(request);

        if (GUEST_MINT_PATH.equals(path) && !isGuestMintAllowed(request)) {
            guestMintsRejected.increment();
            rateLimiter.write429(response, 60L);
            return;
        }
        if (isAllowed(bucketScope, isAuthPath)) {
            filterChain.doFilter(request, response);
        } else {
            long replenishRate = isAuthPath ? props.authReplenishRate() : props.defaultReplenishRate();
            rateLimiter.write429(response, RateLimiter.retryAfterSeconds(replenishRate));
        }
    }

    private String resolveBucketScope(HttpServletRequest request) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof ApiKeyAuthentication keyAuth) {
            return "apikey:" + keyAuth.getKeyId();
        }
        if (auth instanceof JwtAuthentication jwtAuth) {
            if (jwtAuth.getClaims().roles().contains(Roles.GUEST)) {
                return "guest:" + jwtAuth.getUserId();
            }
            return "tenant:" + jwtAuth.getTenantId();
        }
        return RateLimiter.ipScope(request);
    }

    private boolean isGuestMintAllowed(HttpServletRequest request) {
        int perMinute = guestProps.mintsPerMinute();
        return rateLimiter.consume(RateLimiter.ipScope(request), "guest-mint", perMinute / 60.0, perMinute, true);
    }

    private boolean isAllowed(String bucketScope, boolean isAuthPath) {
        String routeClass = isAuthPath ? "auth" : "default";
        return rateLimiter.consume(bucketScope, routeClass,
            isAuthPath ? props.authReplenishRate() : props.defaultReplenishRate(),
            isAuthPath ? props.authBurstCapacity() : props.defaultBurstCapacity(),
            isAuthPath);
    }
}
