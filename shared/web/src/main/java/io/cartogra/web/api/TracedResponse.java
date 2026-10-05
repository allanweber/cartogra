package io.cartogra.web.api;

import io.cartogra.common.api.ApiResponse;
import io.opentelemetry.api.trace.Span;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Builds the standard envelope response: body {@code traceId} and the {@code X-Trace-Id} header
 * are always the same OTel trace id, so they cannot drift apart between controllers.
 */
public final class TracedResponse {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    private TracedResponse() {
    }

    public static <T> ResponseEntity<ApiResponse<T>> ok(T data) {
        return respond(HttpStatus.OK, data);
    }

    public static <T> ResponseEntity<ApiResponse<T>> created(T data) {
        return respond(HttpStatus.CREATED, data);
    }

    public static ResponseEntity<Void> accepted() {
        return ResponseEntity.accepted().header(TRACE_ID_HEADER, currentTraceId()).build();
    }

    public static ResponseEntity<Void> noContent() {
        return ResponseEntity.noContent().header(TRACE_ID_HEADER, currentTraceId()).build();
    }

    private static <T> ResponseEntity<ApiResponse<T>> respond(HttpStatus status, T data) {
        String traceId = currentTraceId();
        return ResponseEntity.status(status)
                .header(TRACE_ID_HEADER, traceId)
                .body(new ApiResponse<>(data, traceId));
    }

    private static String currentTraceId() {
        return Span.current().getSpanContext().getTraceId();
    }
}
