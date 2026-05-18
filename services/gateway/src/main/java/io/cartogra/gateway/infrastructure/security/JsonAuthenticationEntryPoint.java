package io.cartogra.gateway.infrastructure.security;

import io.cartogra.gateway.infrastructure.tracing.TraceContext;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

public class JsonAuthenticationEntryPoint implements ServerAuthenticationEntryPoint {

    private final TraceContext traceContext;

    public JsonAuthenticationEntryPoint(TraceContext traceContext) {
        this.traceContext = traceContext;
    }

    @Override
    public Mono<Void> commence(ServerWebExchange exchange, AuthenticationException ex) {
        return Mono.deferContextual(ctx -> {
            String traceId = traceContext.currentTraceId(ctx);
            String body = """
                {"error":{"code":"UNAUTHORIZED","message":"Authentication required"},"traceId":"%s"}
                """.formatted(traceId).strip();
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
            exchange.getResponse().getHeaders().set("X-Trace-Id", traceId);
            DataBuffer buffer = exchange.getResponse().bufferFactory()
                .wrap(body.getBytes(StandardCharsets.UTF_8));
            return exchange.getResponse().writeWith(Mono.just(buffer));
        });
    }
}
