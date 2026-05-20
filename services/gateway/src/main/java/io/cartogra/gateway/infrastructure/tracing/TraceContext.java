package io.cartogra.gateway.infrastructure.tracing;

import io.opentelemetry.api.trace.Span;
import org.springframework.stereotype.Component;

@Component
public class TraceContext {

    private static final String INVALID_TRACE_ID = "00000000000000000000000000000000";

    public String currentTraceId() {
        String id = Span.current().getSpanContext().getTraceId();
        return (id == null || id.equals(INVALID_TRACE_ID)) ? INVALID_TRACE_ID : id;
    }
}
