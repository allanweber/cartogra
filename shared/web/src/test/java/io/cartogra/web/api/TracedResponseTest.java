package io.cartogra.web.api;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Scope;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class TracedResponseTest {

    private static final String TRACE_ID = "a3f1c8d2a3f1c8d2a3f1c8d2a3f1c8d2";

    private static Span span() {
        return Span.wrap(SpanContext.create(TRACE_ID, "b7ad6b7169203331", TraceFlags.getSampled(), TraceState.getDefault()));
    }

    @Test
    void okCarriesTheSameTraceIdInHeaderAndBody() {
        try (Scope _ = span().makeCurrent()) {
            var response = TracedResponse.ok("payload");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getHeaders().getFirst("X-Trace-Id")).isEqualTo(TRACE_ID);
            assertThat(response.getBody().traceId()).isEqualTo(TRACE_ID);
            assertThat(response.getBody().data()).isEqualTo("payload");
        }
    }

    @Test
    void createdUses201() {
        try (Scope _ = span().makeCurrent()) {
            assertThat(TracedResponse.created("x").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }
    }

    @Test
    void noContentHasTheTraceHeaderAndNoBody() {
        try (Scope _ = span().makeCurrent()) {
            var response = TracedResponse.noContent();

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(response.getHeaders().getFirst("X-Trace-Id")).isEqualTo(TRACE_ID);
            assertThat(response.getBody()).isNull();
        }
    }
}
