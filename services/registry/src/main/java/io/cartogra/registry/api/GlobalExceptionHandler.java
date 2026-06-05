package io.cartogra.registry.api;

import io.cartogra.common.api.ApiError;
import io.cartogra.common.api.ApiErrorResponse;
import io.cartogra.common.api.ErrorCodes;
import io.cartogra.registry.domain.exception.DuplicateServiceNameException;
import io.cartogra.registry.domain.exception.DuplicateTeamNameException;
import io.cartogra.registry.domain.exception.InvalidHealthEndpointException;
import io.cartogra.registry.domain.exception.ScmConnectionNotFoundException;
import io.cartogra.registry.domain.exception.ServiceNotFoundException;
import io.cartogra.registry.domain.exception.TeamNotFoundException;
import io.opentelemetry.api.trace.Span;
import org.springframework.dao.DuplicateKeyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ServiceNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleServiceNotFound(ServiceNotFoundException ex) {
        String traceId = Span.current().getSpanContext().getTraceId();
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header("X-Trace-Id", traceId)
                .body(new ApiErrorResponse(ApiError.of(ErrorCodes.NOT_FOUND, ex.getMessage()), traceId));
    }

    @ExceptionHandler(TeamNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleTeamNotFound(TeamNotFoundException ex) {
        String traceId = Span.current().getSpanContext().getTraceId();
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header("X-Trace-Id", traceId)
                .body(new ApiErrorResponse(ApiError.of(ErrorCodes.NOT_FOUND, ex.getMessage()), traceId));
    }

    @ExceptionHandler(ScmConnectionNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleScmConnectionNotFound(ScmConnectionNotFoundException ex) {
        String traceId = Span.current().getSpanContext().getTraceId();
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header("X-Trace-Id", traceId)
                .body(new ApiErrorResponse(ApiError.of(ErrorCodes.NOT_FOUND, ex.getMessage()), traceId));
    }

    @ExceptionHandler(InvalidHealthEndpointException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidHealthEndpoint(InvalidHealthEndpointException ex) {
        String traceId = Span.current().getSpanContext().getTraceId();
        return ResponseEntity.status(HttpStatusCode.valueOf(422))
                .header("X-Trace-Id", traceId)
                .body(new ApiErrorResponse(ApiError.of(ErrorCodes.INVALID_HEALTH_ENDPOINT, ex.getMessage()), traceId));
    }

    @ExceptionHandler(DuplicateServiceNameException.class)
    public ResponseEntity<ApiErrorResponse> handleDuplicateServiceName(DuplicateServiceNameException ex) {
        String traceId = Span.current().getSpanContext().getTraceId();
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header("X-Trace-Id", traceId)
                .body(new ApiErrorResponse(ApiError.of(ErrorCodes.CONFLICT, ex.getMessage()), traceId));
    }

    @ExceptionHandler(DuplicateTeamNameException.class)
    public ResponseEntity<ApiErrorResponse> handleDuplicateTeamName(DuplicateTeamNameException ex) {
        String traceId = Span.current().getSpanContext().getTraceId();
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header("X-Trace-Id", traceId)
                .body(new ApiErrorResponse(ApiError.of(ErrorCodes.CONFLICT, ex.getMessage()), traceId));
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ApiErrorResponse> handleDuplicateKey(DuplicateKeyException ex) {
        String traceId = Span.current().getSpanContext().getTraceId();
        logger.warn("Duplicate key violation", ex);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header("X-Trace-Id", traceId)
                .body(new ApiErrorResponse(ApiError.of(ErrorCodes.CONFLICT, "A record with the same unique value already exists"), traceId));
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

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ApiErrorResponse> handleNotFound(NoSuchElementException ex) {
        String traceId = Span.current().getSpanContext().getTraceId();
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header("X-Trace-Id", traceId)
                .body(new ApiErrorResponse(ApiError.of(ErrorCodes.NOT_FOUND, ex.getMessage()), traceId));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        String traceId = Span.current().getSpanContext().getTraceId();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .header("X-Trace-Id", traceId)
                .body(new ApiErrorResponse(ApiError.of(ErrorCodes.BAD_REQUEST, ex.getMessage()), traceId));
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNoHandler(NoHandlerFoundException ex) {
        String traceId = Span.current().getSpanContext().getTraceId();
        String message = ex.getRequestURL() + " not found";
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header("X-Trace-Id", traceId)
                .body(new ApiErrorResponse(ApiError.of(ErrorCodes.NOT_FOUND, message), traceId));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        String traceId = Span.current().getSpanContext().getTraceId();
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .header("X-Trace-Id", traceId)
                .body(new ApiErrorResponse(ApiError.of(ErrorCodes.BAD_REQUEST, ex.getMessage()), traceId));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiErrorResponse> handleMissingHeader(MissingRequestHeaderException ex) {
        String traceId = Span.current().getSpanContext().getTraceId();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .header("X-Trace-Id", traceId)
                .body(new ApiErrorResponse(ApiError.of(ErrorCodes.BAD_REQUEST, "Missing required header: " + ex.getHeaderName()), traceId));
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
