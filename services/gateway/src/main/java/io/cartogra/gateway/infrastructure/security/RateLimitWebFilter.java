package io.cartogra.gateway.infrastructure.security;

import io.cartogra.common.api.ApiError;
import io.cartogra.common.api.ApiErrorResponse;
import io.cartogra.gateway.config.RateLimitProperties;
import io.cartogra.gateway.infrastructure.tracing.TraceContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.List;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RateLimitWebFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitWebFilter.class);

    private final RateLimitProperties props;
    private final ReactiveStringRedisTemplate redisTemplate;
    private final RedisScript<String> rateLimitScript;
    private final ObjectMapper objectMapper;
    private final TraceContext traceContext;

    public RateLimitWebFilter(RateLimitProperties props,
                               ReactiveStringRedisTemplate redisTemplate,
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
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        log.debug("RateLimitWebFilter invoked: enabled={} path={}", props.enabled(), path);

        if (!props.enabled()) {
            return chain.filter(exchange);
        }

        boolean isAuthPath = path.startsWith("/v1/auth/");

        return clientIp(exchange)
            .flatMap(ip -> isAllowed(ip, isAuthPath))
            .flatMap(allowed -> allowed ? chain.filter(exchange) : write429(exchange));
    }

    private Mono<String> clientIp(ServerWebExchange exchange) {
        return Mono.justOrEmpty(exchange.getRequest().getRemoteAddress())
            .map(InetSocketAddress::getHostString)
            .defaultIfEmpty("unknown");
    }

    private Mono<Boolean> isAllowed(String clientIp, boolean isAuthPath) {
        String bucket = isAuthPath ? "rate_limit:auth:" + clientIp : "rate_limit:default:" + clientIp;
        List<String> keys = List.of(bucket + ".tokens", bucket + ".timestamp");
        List<String> args = List.of(
            String.valueOf(isAuthPath ? props.authReplenishRate() : props.defaultReplenishRate()),
            String.valueOf(isAuthPath ? props.authBurstCapacity() : props.defaultBurstCapacity()),
            String.valueOf(Instant.now().getEpochSecond())
        );
        return redisTemplate.execute(rateLimitScript, keys, args)
            .next()
            .map("1"::equals)
            .defaultIfEmpty(true)
            .doOnError(e -> log.error("Rate limit Redis error for bucket '{}': {}", bucket, e.getMessage()))
            .onErrorReturn(true);
    }

    private Mono<Void> write429(ServerWebExchange exchange) {
        return Mono.deferContextual(ctx -> {
            String traceId = traceContext.currentTraceId(ctx);
            ApiErrorResponse body = new ApiErrorResponse(
                ApiError.of("RATE_LIMITED", "Too many requests"), traceId);
            exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
            exchange.getResponse().getHeaders().set("X-Trace-Id", traceId);
            exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
            byte[] bytes = objectMapper.writeValueAsBytes(body);
            DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
            return exchange.getResponse().writeWith(Mono.just(buffer));
        });
    }
}
