package io.cartogra.gateway.config;

import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class GlobalTracingFilter implements GlobalFilter, Ordered {

    private final Tracer tracer;

    public GlobalTracingFilter(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String method = exchange.getRequest().getMethod() != null
                ? exchange.getRequest().getMethod().name()
                : "UNKNOWN";
        String path = exchange.getRequest().getPath().value();
        Span span = this.tracer.spanBuilder(method + " " + path)
                .setSpanKind(SpanKind.SERVER)
                .startSpan();

        exchange.getResponse().getHeaders().set("X-Trace-Id", span.getSpanContext().getTraceId());

        try (Scope ignored = span.makeCurrent()) {
            return chain.filter(exchange)
                    .doOnError(span::recordException)
                    .doFinally(signalType -> span.end());
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 10;
    }
}
