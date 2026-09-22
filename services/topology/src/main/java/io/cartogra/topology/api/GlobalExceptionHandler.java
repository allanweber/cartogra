package io.cartogra.topology.api;

import io.cartogra.common.api.ApiError;
import io.cartogra.common.api.ApiErrorResponse;
import io.cartogra.common.api.ErrorCodes;
import io.cartogra.topology.domain.exception.BackfillFailedException;
import io.cartogra.topology.domain.exception.DependencyNotFoundException;
import io.cartogra.topology.domain.exception.DriftNotFoundException;
import io.cartogra.topology.domain.exception.DuplicateDependencyException;
import io.cartogra.topology.domain.exception.SelfDependencyException;
import io.cartogra.topology.domain.exception.UnknownServiceNodeException;
import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(DependencyNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleDependencyNotFound(DependencyNotFoundException ex) {
        return respond(HttpStatus.NOT_FOUND, ErrorCodes.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(DriftNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleDriftNotFound(DriftNotFoundException ex) {
        return respond(HttpStatus.NOT_FOUND, ErrorCodes.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(SelfDependencyException.class)
    public ResponseEntity<ApiErrorResponse> handleSelfDependency(SelfDependencyException ex) {
        return respond(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCodes.SELF_DEPENDENCY, ex.getMessage());
    }

    @ExceptionHandler(UnknownServiceNodeException.class)
    public ResponseEntity<ApiErrorResponse> handleUnknownServiceNode(UnknownServiceNodeException ex) {
        return respond(HttpStatus.NOT_FOUND, ErrorCodes.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(DuplicateDependencyException.class)
    public ResponseEntity<ApiErrorResponse> handleDuplicateDependency(DuplicateDependencyException ex) {
        return respond(HttpStatus.CONFLICT, ErrorCodes.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(RestClientException.class)
    public ResponseEntity<ApiErrorResponse> handleRestClientException(RestClientException ex) {
        logger.warn("Downstream service call failed", ex);
        return respond(HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.SERVICE_UNAVAILABLE,
                "A downstream service is unavailable");
    }

    @ExceptionHandler(BackfillFailedException.class)
    public ResponseEntity<ApiErrorResponse> handleBackfillFailed(BackfillFailedException ex) {
        logger.warn("Backfill failed", ex);
        String traceId = traceId();
        var error = new ApiError(ErrorCodes.SERVICE_UNAVAILABLE, ex.getMessage(),
                Map.of("nodesUpserted", ex.nodesUpserted(), "failedAtOffset", ex.failedAtOffset()));
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("X-Trace-Id", traceId)
                .body(new ApiErrorResponse(error, traceId));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        return respond(HttpStatus.FORBIDDEN, ErrorCodes.FORBIDDEN, ex.getMessage());
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ApiErrorResponse> handleDuplicateKey(DuplicateKeyException ex) {
        logger.warn("Duplicate key violation", ex);
        return respond(HttpStatus.CONFLICT, ErrorCodes.CONFLICT, "A record with the same unique value already exists");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleMessageNotReadable(HttpMessageNotReadableException ex) {
        return respond(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_ERROR, "Malformed or unreadable request body");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, Object> details = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(FieldError::getField, FieldError::getDefaultMessage, (a, _) -> a));
        String traceId = traceId();
        return ResponseEntity.badRequest()
                .header("X-Trace-Id", traceId)
                .body(new ApiErrorResponse(new ApiError(ErrorCodes.VALIDATION_ERROR, "Validation failed", details), traceId));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ApiErrorResponse> handleNotFound(NoSuchElementException ex) {
        return respond(HttpStatus.NOT_FOUND, ErrorCodes.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        return respond(HttpStatus.BAD_REQUEST, ErrorCodes.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNoHandler(NoHandlerFoundException ex) {
        return respond(HttpStatus.NOT_FOUND, ErrorCodes.NOT_FOUND, ex.getRequestURL() + " not found");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return respond(HttpStatus.METHOD_NOT_ALLOWED, ErrorCodes.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiErrorResponse> handleMissingHeader(MissingRequestHeaderException ex) {
        return respond(HttpStatus.BAD_REQUEST, ErrorCodes.BAD_REQUEST, "Missing required header: " + ex.getHeaderName());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return respond(HttpStatus.BAD_REQUEST, ErrorCodes.BAD_REQUEST,
                "Invalid value for parameter '%s': %s".formatted(ex.getName(), ex.getValue()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleGeneral(Exception ex) {
        logger.error("INTERNAL_ERROR", ex);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCodes.INTERNAL_ERROR, "An unexpected error occurred");
    }

    private static ResponseEntity<ApiErrorResponse> respond(HttpStatus status, String code, String message) {
        String traceId = traceId();
        return ResponseEntity.status(status)
                .header("X-Trace-Id", traceId)
                .body(new ApiErrorResponse(ApiError.of(code, message), traceId));
    }

    private static String traceId() {
        return Span.current().getSpanContext().getTraceId();
    }
}
