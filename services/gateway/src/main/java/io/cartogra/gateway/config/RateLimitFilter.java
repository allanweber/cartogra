package io.cartogra.gateway.config;

import io.cartogra.common.api.ApiError;
import io.cartogra.common.api.ApiErrorResponse;
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

    private final RateLimitProperties props;
    private final StringRedisTemplate redisTemplate;
    private final RedisScript<String> rateLimitScript;
    private final ObjectMapper objectMapper;
    private final TraceContext traceContext;

    public RateLimitFilter(RateLimitProperties props,
                           StringRedisTemplate redisTemplate,
                           RedisScript<String> rateLimitScript,
                           ObjectMapper objectMapper,
                           TraceContext traceContext) {
        this.props = props;
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
        String clientIp = resolveClientIp(request);

        if (isAllowed(clientIp, isAuthPath)) {
            filterChain.doFilter(request, response);
        } else {
            write429(response);
        }
    }

    private String resolveClientIp(HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        return (ip != null) ? ip : "unknown";
    }

    private boolean isAllowed(String clientIp, boolean isAuthPath) {
        String bucket = isAuthPath ? "rate_limit:auth:" + clientIp : "rate_limit:default:" + clientIp;
        List<String> keys = List.of(bucket + ".tokens", bucket + ".timestamp");
        List<String> args = List.of(
            String.valueOf(isAuthPath ? props.authReplenishRate() : props.defaultReplenishRate()),
            String.valueOf(isAuthPath ? props.authBurstCapacity() : props.defaultBurstCapacity()),
            String.valueOf(Instant.now().toEpochMilli())
        );
        try {
            String result = redisTemplate.execute(rateLimitScript, keys, args.toArray());
            return !"0".equals(result);
        } catch (Exception e) {
            log.error("Rate limit Redis error for bucket '{}': {}", bucket, e.getMessage());
            return true;
        }
    }

    private void write429(HttpServletResponse response) throws IOException {
        String traceId = traceContext.currentTraceId();
        ApiErrorResponse body = new ApiErrorResponse(
            ApiError.of("RATE_LIMITED", "Too many requests"), traceId);
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("X-Trace-Id", traceId);
        response.getOutputStream().write(objectMapper.writeValueAsBytes(body));
    }
}
