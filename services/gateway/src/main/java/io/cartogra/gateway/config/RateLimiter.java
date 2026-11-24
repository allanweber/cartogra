package io.cartogra.gateway.config;

import io.cartogra.common.api.ApiError;
import io.cartogra.common.api.ApiErrorResponse;
import io.cartogra.gateway.infrastructure.tracing.TraceContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

/**
 * The token buckets of the gateway, in Redis. {@link RateLimitFilter} and {@link ApiKeyAuthenticationFilter}
 * both use it. It owns the bucket key, the consume call, the fail-open or fail-closed rule on a Redis error,
 * and the 429 response.
 */
@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    private final RateLimitProperties props;
    private final StringRedisTemplate redisTemplate;
    private final RedisScript<String> rateLimitScript;
    private final ObjectMapper objectMapper;
    private final TraceContext traceContext;

    public RateLimiter(RateLimitProperties props,
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

    public boolean enabled() {
        return props.enabled();
    }

    /** The bucket name, for a scope (for example "ip:1.2.3.4") and a suffix (for example "default"). */
    static String bucketKey(String scope, String suffix) {
        return "rate_limit:" + scope + ":" + suffix;
    }

    /** The Retry-After value, in seconds, for a bucket that refills at this rate. */
    static long retryAfterSeconds(double replenishPerSecond) {
        return Math.max(1L, (long) Math.ceil(1.0 / replenishPerSecond));
    }

    static String ipScope(HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        return "ip:" + ((ip != null) ? ip : "unknown");
    }

    /** Takes a token from the bucket of the scope and suffix. Returns true if the request can go on. */
    public boolean consume(String scope, String suffix, double replenishPerSecond, int capacity,
            boolean isAuthPath) {
        String bucket = bucketKey(scope, suffix);
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

    /**
     * Charges the request to the IP bucket of the API key allowlist. {@link ApiKeyAuthenticationFilter} calls it
     * before the key lookup, so that a flood of missing or bad keys gets 429 and does not reach the database.
     * The bucket is separate from the other IP buckets. A valid key is also charged to its own bucket later.
     * Returns true if the request can go on. If it returns false, the 429 response is written.
     */
    public boolean allowBeforeKeyLookup(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        if (!props.enabled()) {
            return true;
        }
        if (consume(ipScope(request), "apikey-lookup",
                props.defaultReplenishRate(), props.defaultBurstCapacity(), false)) {
            return true;
        }
        write429(response, retryAfterSeconds(props.defaultReplenishRate()));
        return false;
    }

    public void write429(HttpServletResponse response, long retryAfter) throws IOException {
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
