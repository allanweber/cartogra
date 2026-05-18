package io.cartogra.gateway.config;

import io.cartogra.gateway.infrastructure.tracing.TraceContext;
import org.reactivestreams.Publisher;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GlobalTracingFilter implements WebFilter {

    private final TraceContext traceContext;

    public GlobalTracingFilter(TraceContext traceContext) {
        this.traceContext = traceContext;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpResponseDecorator decoratedResponse = new ServerHttpResponseDecorator(exchange.getResponse()) {
            @Override
            public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                return Mono.deferContextual(ctx -> {
                    getHeaders().set("X-Trace-Id", traceContext.currentTraceId(ctx));
                    return super.writeWith(body);
                });
            }

            @Override
            public Mono<Void> writeAndFlushWith(Publisher<? extends Publisher<? extends DataBuffer>> body) {
                return Mono.deferContextual(ctx -> {
                    getHeaders().set("X-Trace-Id", traceContext.currentTraceId(ctx));
                    return super.writeAndFlushWith(body);
                });
            }
        };

        return chain.filter(exchange.mutate().response(decoratedResponse).build());
    }
}
