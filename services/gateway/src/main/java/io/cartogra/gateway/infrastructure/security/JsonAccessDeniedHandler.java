package io.cartogra.gateway.infrastructure.security;

import io.cartogra.gateway.infrastructure.tracing.TraceContext;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

public class JsonAccessDeniedHandler implements ServerAccessDeniedHandler {

    private final TraceContext traceContext;

    public JsonAccessDeniedHandler(TraceContext traceContext) {
        this.traceContext = traceContext;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, AccessDeniedException ex) {
        return Mono.deferContextual(ctx -> {
            String traceId = traceContext.currentTraceId(ctx);
            String body = """
                {"error":{"code":"ACCESS_DENIED","message":"Insufficient permissions"},"traceId":"%s"}
                """.formatted(traceId).strip();
            exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
            exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
            exchange.getResponse().getHeaders().set("X-Trace-Id", traceId);
            DataBuffer buffer = exchange.getResponse().bufferFactory()
                .wrap(body.getBytes(StandardCharsets.UTF_8));
            return exchange.getResponse().writeWith(Mono.just(buffer));
        });
    }
}
