package io.cartogra.gateway.api;

import io.cartogra.common.api.ApiError;
import io.cartogra.common.api.ApiErrorResponse;
import io.cartogra.common.api.ErrorCodes;
import io.cartogra.gateway.domain.exception.ConflictException;
import io.cartogra.gateway.domain.exception.InvalidOAuthStateException;
import io.cartogra.gateway.domain.exception.InvalidOtpException;
import io.cartogra.gateway.domain.exception.UnauthorizedException;
import io.cartogra.gateway.domain.exception.UnverifiedEmailException;
import io.cartogra.gateway.infrastructure.tracing.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tools.jackson.databind.exc.InvalidFormatException;

import java.util.Arrays;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final TraceContext traceContext;

    public GlobalExceptionHandler(TraceContext traceContext) {
        this.traceContext = traceContext;
    }

    @ExceptionHandler({UnauthorizedException.class, UnverifiedEmailException.class})
    public ResponseEntity<ApiErrorResponse> handleUnauthorized(RuntimeException ex) {
        log.warn("Unauthorized: {}", ex.getMessage());
        return error(HttpStatus.UNAUTHORIZED, ErrorCodes.UNAUTHORIZED, ex.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiErrorResponse> handleConflict(ConflictException ex) {
        log.warn("Conflict: {}", ex.getMessage());
        return error(HttpStatus.CONFLICT, ErrorCodes.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler({InvalidOtpException.class, InvalidOAuthStateException.class, IllegalArgumentException.class})
    public ResponseEntity<ApiErrorResponse> handleBadRequest(RuntimeException ex) {
        log.warn("Bad request: {}", ex.getMessage());
        return error(HttpStatus.BAD_REQUEST, ErrorCodes.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleMessageNotReadable(HttpMessageNotReadableException ex) {
        return error(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_ERROR, enumFormatMessage(ex));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
            .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
            .findFirst()
            .orElse("Validation failed");
        return error(HttpStatus.BAD_REQUEST, ErrorCodes.BAD_REQUEST, message);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNoResource(NoResourceFoundException ex) {
        return error(HttpStatus.NOT_FOUND, ErrorCodes.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCodes.INTERNAL_ERROR, "An unexpected error occurred");
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

    private ResponseEntity<ApiErrorResponse> error(HttpStatus status, String code, String message) {
        String traceId = traceContext.currentTraceId();
        return ResponseEntity.status(status)
            .header("X-Trace-Id", traceId)
            .body(new ApiErrorResponse(ApiError.of(code, message), traceId));
    }
}
