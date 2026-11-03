package io.cartogra.gateway.config;

import io.cartogra.common.api.ApiError;
import io.cartogra.common.api.ApiErrorResponse;
import io.cartogra.common.identity.Roles;
import io.cartogra.gateway.infrastructure.security.JwtAuthentication;
import io.cartogra.gateway.infrastructure.tracing.TraceContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private static final String GUEST_MINT_PATH = "/api/auth/guest";

    private final RateLimitProperties props;
    private final GuestProperties guestProps;
    private final StringRedisTemplate redisTemplate;
    private final RedisScript<String> rateLimitScript;
    private final ObjectMapper objectMapper;
    private final TraceContext traceContext;

    public RateLimitFilter(RateLimitProperties props,
                           GuestProperties guestProps,
                           StringRedisTemplate redisTemplate,
                           RedisScript<String> rateLimitScript,
                           ObjectMapper objectMapper,
                           TraceContext traceContext) {
        this.props = props;
        this.guestProps = guestProps;
        this.redisTemplate = redisTemplate;
        this.rateLimitScript = rateLimitScript;
        this.objectMapper = objectMapper;
        this.traceContext = traceContext;
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
            write429(response, 60L);
            return;
        }
        if (isAllowed(bucketScope, isAuthPath)) {
            filterChain.doFilter(request, response);
        } else {
            long replenishRate = isAuthPath ? props.authReplenishRate() : props.defaultReplenishRate();
            write429(response, Math.max(1L, (long) Math.ceil(1.0 / replenishRate)));
        }
    }

    private String resolveBucketScope(HttpServletRequest request) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthentication jwtAuth) {
            if (jwtAuth.getClaims().roles().contains(Roles.GUEST)) {
                return "guest:" + jwtAuth.getUserId();
            }
            return "tenant:" + jwtAuth.getTenantId();
        }
        return ipScope(request);
    }

    private static String ipScope(HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        return "ip:" + ((ip != null) ? ip : "unknown");
    }

    private boolean isGuestMintAllowed(HttpServletRequest request) {
        int perMinute = guestProps.mintsPerMinute();
        return consume("rate_limit:" + ipScope(request) + ":guest-mint", perMinute / 60.0, perMinute, true);
    }

    private boolean isAllowed(String bucketScope, boolean isAuthPath) {
        String routeClass = isAuthPath ? "auth" : "default";
        return consume("rate_limit:" + bucketScope + ":" + routeClass,
            isAuthPath ? props.authReplenishRate() : props.defaultReplenishRate(),
            isAuthPath ? props.authBurstCapacity() : props.defaultBurstCapacity(),
            isAuthPath);
    }

    private boolean consume(String bucket, double replenishPerSecond, int capacity, boolean isAuthPath) {
        List<String> keys = List.of(bucket + ".tokens", bucket + ".timestamp");
        List<String> args = List.of(
            String.valueOf(replenishPerSecond),
            String.valueOf(capacity),
            String.valueOf(Instant.now().toEpochMilli())
        );
        try {
            String result = redisTemplate.execute(rateLimitScript, keys, args.toArray());
            return !"0".equals(result);
        } catch (Exception e) {
            log.error("Rate limit Redis error for bucket '{}': {}", bucket, e.getMessage());
            if (isAuthPath && props.failClosedOnAuthRoutes()) {
                return false;
            }
            return true;
        }
    }

    private void write429(HttpServletResponse response, long retryAfter) throws IOException {
        String traceId = traceContext.currentTraceId();
        ApiErrorResponse body = new ApiErrorResponse(
            ApiError.of("RATE_LIMITED", "Too many requests"), traceId);
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("X-Trace-Id", traceId);
        response.setHeader("Retry-After", String.valueOf(retryAfter));
        response.getOutputStream().write(objectMapper.writeValueAsBytes(body));
    }
}
