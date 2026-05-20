package io.cartogra.gateway.config;

import io.cartogra.gateway.infrastructure.tracing.TraceContext;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class GlobalTracingFilter extends OncePerRequestFilter {

    private final TraceContext traceContext;

    public GlobalTracingFilter(TraceContext traceContext) {
        this.traceContext = traceContext;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        // ServerHttpObservationFilter (at HIGHEST_PRECEDENCE+1) wraps us, so the OTel span is
        // already live here and remains open until after we return — capture it now.
        String traceId = traceContext.currentTraceId();
        String traceparent = buildTraceparent(Span.current().getSpanContext());

        // Inject traceparent into the request so Spring Cloud Gateway MVC forwards it to the
        // upstream (registry, topology, …). Without this, each downstream service starts its
        // own root span and the gateway leg is invisible in Tempo.
        HttpServletRequest wrapped = traceparent != null
                ? new TraceparentRequestWrapper(request, traceparent)
                : request;

        filterChain.doFilter(wrapped, response);

        // Always override whatever X-Trace-Id a downstream set — the gateway's span is the
        // root of this trace and its id is what the client should search for in Tempo/Grafana.
        response.setHeader("X-Trace-Id", traceId);
    }

    private static String buildTraceparent(SpanContext ctx) {
        if (!ctx.isValid()) return null;
        String flags = ctx.getTraceFlags().isSampled() ? "01" : "00";
        return "00-" + ctx.getTraceId() + "-" + ctx.getSpanId() + "-" + flags;
    }

    private static class TraceparentRequestWrapper extends HttpServletRequestWrapper {
        private final String traceparent;

        TraceparentRequestWrapper(HttpServletRequest request, String traceparent) {
            super(request);
            this.traceparent = traceparent;
        }

        @Override
        public String getHeader(String name) {
            if ("traceparent".equalsIgnoreCase(name)) return traceparent;
            return super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            if ("traceparent".equalsIgnoreCase(name)) return Collections.enumeration(List.of(traceparent));
            return super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            List<String> names = new ArrayList<>(Collections.list(super.getHeaderNames()));
            if (names.stream().noneMatch("traceparent"::equalsIgnoreCase)) {
                names.add("traceparent");
            }
            return Collections.enumeration(names);
        }
    }
}
