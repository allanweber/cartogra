package io.cartogra.registry.api;

import io.cartogra.common.api.ApiError;
import io.cartogra.common.api.ApiErrorResponse;
import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        String traceId = Span.current().getSpanContext().getTraceId();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .header("X-Trace-Id", traceId)
                .body(new ApiErrorResponse(ApiError.of("BAD_REQUEST", ex.getMessage()), traceId));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleGeneral(Exception ex) {
        String traceId = Span.current().getSpanContext().getTraceId();
        logger.error("INTERNAL_ERROR", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .header("X-Trace-Id", traceId)
                .body(new ApiErrorResponse(ApiError.of("INTERNAL_ERROR", "An unexpected error occurred"), traceId));
    }
}
