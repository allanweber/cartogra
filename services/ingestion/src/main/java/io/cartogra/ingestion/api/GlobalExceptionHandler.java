package io.cartogra.ingestion.api;

import io.cartogra.common.api.ApiError;
import io.cartogra.common.api.ApiErrorResponse;
import io.cartogra.common.api.ErrorCodes;
import io.cartogra.ingestion.domain.exception.ScmConnectionNotFoundException;
import io.cartogra.ingestion.domain.exception.WebhookConnectionNotFoundException;
import io.cartogra.ingestion.domain.exception.WebhookSignatureInvalidException;
import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import tools.jackson.databind.exc.InvalidFormatException;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ScmConnectionNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleScmConnectionNotFound(ScmConnectionNotFoundException ex) {
        String traceId = Span.current().getSpanContext().getTraceId();
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header("X-Trace-Id", traceId)
                .body(new ApiErrorResponse(ApiError.of(ErrorCodes.NOT_FOUND, ex.getMessage()), traceId));
    }

    @ExceptionHandler(WebhookConnectionNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleWebhookConnectionNotFound(WebhookConnectionNotFoundException ex) {
        String traceId = Span.current().getSpanContext().getTraceId();
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header("X-Trace-Id", traceId)
                .body(new ApiErrorResponse(ApiError.of(ErrorCodes.WEBHOOK_CONNECTION_NOT_FOUND, ex.getMessage()), traceId));
    }

    @ExceptionHandler(WebhookSignatureInvalidException.class)
    public ResponseEntity<ApiErrorResponse> handleWebhookSignatureInvalid(WebhookSignatureInvalidException ex) {
        String traceId = Span.current().getSpanContext().getTraceId();
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header("X-Trace-Id", traceId)
                .body(new ApiErrorResponse(ApiError.of(ErrorCodes.WEBHOOK_SIGNATURE_INVALID, ex.getMessage()), traceId));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleMessageNotReadable(HttpMessageNotReadableException ex) {
        String traceId = Span.current().getSpanContext().getTraceId();
        String message = enumFormatMessage(ex);
        return ResponseEntity.badRequest()
                .header("X-Trace-Id", traceId)
                .body(new ApiErrorResponse(ApiError.of(ErrorCodes.VALIDATION_ERROR, message), traceId));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String traceId = Span.current().getSpanContext().getTraceId();
        Map<String, Object> details = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(FieldError::getField, FieldError::getDefaultMessage, (a, _) -> a));
        return ResponseEntity.badRequest()
                .header("X-Trace-Id", traceId)
                .body(new ApiErrorResponse(new ApiError(ErrorCodes.VALIDATION_ERROR, "Validation failed", details), traceId));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiErrorResponse> handleMissingHeader(MissingRequestHeaderException ex) {
        String traceId = Span.current().getSpanContext().getTraceId();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .header("X-Trace-Id", traceId)
                .body(new ApiErrorResponse(ApiError.of(ErrorCodes.BAD_REQUEST, "Missing required header: " + ex.getHeaderName()), traceId));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        String traceId = Span.current().getSpanContext().getTraceId();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .header("X-Trace-Id", traceId)
                .body(new ApiErrorResponse(ApiError.of(ErrorCodes.BAD_REQUEST, ex.getMessage()), traceId));
    }

    private static String enumFormatMessage(HttpMessageNotReadableException ex) {
        if (ex.getCause() instanceof InvalidFormatException ife
                && ife.getTargetType() != null
                && ife.getTargetType().isEnum()) {
            String accepted = Arrays.stream(ife.getTargetType().getEnumConstants())
                    .map(e -> ((Enum<?>) e).name().toLowerCase())
                    .collect(Collectors.joining(", "));
            return "Invalid value '%s'. Accepted values: %s".formatted(ife.getValue(), accepted);
        }
        return "Malformed or unreadable request body";
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleGeneral(Exception ex) {
        String traceId = Span.current().getSpanContext().getTraceId();
        logger.error("INTERNAL_ERROR", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .header("X-Trace-Id", traceId)
                .body(new ApiErrorResponse(ApiError.of(ErrorCodes.INTERNAL_ERROR, "An unexpected error occurred"), traceId));
    }
}
