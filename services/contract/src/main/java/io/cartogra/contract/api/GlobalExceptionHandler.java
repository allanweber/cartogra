package io.cartogra.contract.api;

import io.cartogra.common.api.ApiError;
import io.cartogra.common.api.ApiErrorResponse;
import io.cartogra.common.api.ErrorCodes;
import io.cartogra.contract.domain.exception.BaselineNotFoundException;
import io.cartogra.contract.domain.exception.CheckServiceNotFoundException;
import io.cartogra.contract.domain.exception.ContractAlreadyExistsException;
import io.cartogra.contract.domain.exception.ContractNotFoundException;
import io.cartogra.contract.domain.exception.InvalidParameterException;
import io.cartogra.contract.domain.exception.InvalidSpecException;
import io.cartogra.contract.domain.exception.MissingFieldException;
import io.cartogra.contract.domain.exception.NotificationRuleConflictException;
import io.cartogra.contract.domain.exception.NotificationRuleDisabledException;
import io.cartogra.contract.domain.exception.NotificationRuleNotFoundException;
import io.cartogra.contract.domain.exception.RegistryUnavailableException;
import io.cartogra.contract.domain.exception.ServiceAmbiguousException;
import io.cartogra.contract.domain.exception.ServiceDeletedException;
import io.cartogra.contract.domain.exception.ServiceNotFoundException;
import io.cartogra.contract.domain.exception.VersionNotFoundException;
import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ContractNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleContractNotFound(ContractNotFoundException ex) {
        return respond(HttpStatus.NOT_FOUND, ErrorCodes.NOT_FOUND, ex.getMessage(), Map.of());
    }

    @ExceptionHandler(VersionNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleVersionNotFound(VersionNotFoundException ex) {
        return respond(HttpStatus.NOT_FOUND, ErrorCodes.NOT_FOUND, ex.getMessage(), Map.of());
    }

    @ExceptionHandler(ServiceNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleServiceNotFound(ServiceNotFoundException ex) {
        return respond(HttpStatus.NOT_FOUND, ErrorCodes.NOT_FOUND, ex.getMessage(), Map.of());
    }

    @ExceptionHandler(CheckServiceNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleCheckServiceNotFound(CheckServiceNotFoundException ex) {
        return respond(HttpStatus.NOT_FOUND, ErrorCodes.SERVICE_NOT_FOUND, ex.getMessage(), Map.of());
    }

    @ExceptionHandler(ServiceAmbiguousException.class)
    public ResponseEntity<ApiErrorResponse> handleServiceAmbiguous(ServiceAmbiguousException ex) {
        return respond(HttpStatus.CONFLICT, ErrorCodes.SERVICE_AMBIGUOUS, ex.getMessage(), Map.of());
    }

    @ExceptionHandler(BaselineNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleBaselineNotFound(BaselineNotFoundException ex) {
        return respond(HttpStatus.NOT_FOUND, ErrorCodes.BASELINE_NOT_FOUND, ex.getMessage(), Map.of());
    }

    @ExceptionHandler(RegistryUnavailableException.class)
    public ResponseEntity<ApiErrorResponse> handleRegistryUnavailable(RegistryUnavailableException ex) {
        return respond(HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.SERVICE_UNAVAILABLE, ex.getMessage(), Map.of());
    }

    @ExceptionHandler(ServiceDeletedException.class)
    public ResponseEntity<ApiErrorResponse> handleServiceDeleted(ServiceDeletedException ex) {
        return respond(HttpStatus.NOT_FOUND, ErrorCodes.NOT_FOUND, ex.getMessage(), Map.of());
    }

    @ExceptionHandler(ContractAlreadyExistsException.class)
    public ResponseEntity<ApiErrorResponse> handleContractAlreadyExists(ContractAlreadyExistsException ex) {
        return respond(HttpStatus.CONFLICT, ErrorCodes.CONFLICT, ex.getMessage(),
                Map.of("contractId", ex.contractId()));
    }

    @ExceptionHandler(InvalidSpecException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidSpec(InvalidSpecException ex) {
        return respond(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCodes.INVALID_SPEC, ex.getMessage(),
                Map.of("messages", ex.messages()));
    }

    @ExceptionHandler(MissingFieldException.class)
    public ResponseEntity<ApiErrorResponse> handleMissingField(MissingFieldException ex) {
        return respond(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_ERROR, "Validation failed",
                Map.of(ex.field(), "must not be null"));
    }

    @ExceptionHandler(InvalidParameterException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidParameter(InvalidParameterException ex) {
        return respond(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_ERROR, "Validation failed",
                Map.of(ex.parameter(), ex.reason()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        return respond(HttpStatus.FORBIDDEN, ErrorCodes.FORBIDDEN, ex.getMessage(), Map.of());
    }

    @ExceptionHandler(NotificationRuleNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleRuleNotFound(NotificationRuleNotFoundException ex) {
        return respond(HttpStatus.NOT_FOUND, ErrorCodes.NOT_FOUND, ex.getMessage(), Map.of());
    }

    @ExceptionHandler(NotificationRuleConflictException.class)
    public ResponseEntity<ApiErrorResponse> handleRuleConflict(NotificationRuleConflictException ex) {
        return respond(HttpStatus.CONFLICT, ErrorCodes.CONFLICT, ex.getMessage(), Map.of());
    }

    @ExceptionHandler(NotificationRuleDisabledException.class)
    public ResponseEntity<ApiErrorResponse> handleRuleDisabled(NotificationRuleDisabledException ex) {
        return respond(HttpStatus.CONFLICT, ErrorCodes.VALIDATION_ERROR, ex.getMessage(), Map.of());
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ApiErrorResponse> handleDuplicateKey(DuplicateKeyException ex) {
        logger.warn("Duplicate key violation", ex);
        return respond(HttpStatus.CONFLICT, ErrorCodes.CONFLICT, "A record with the same unique value already exists",
                Map.of());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleMessageNotReadable(HttpMessageNotReadableException ex) {
        return respond(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_ERROR, "Malformed or unreadable request body",
                Map.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, Object> details = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(FieldError::getField, FieldError::getDefaultMessage, (a, _) -> a));
        return respond(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_ERROR, "Validation failed", details);
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNoHandler(NoHandlerFoundException ex) {
        return respond(HttpStatus.NOT_FOUND, ErrorCodes.NOT_FOUND, ex.getRequestURL() + " not found", Map.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return respond(HttpStatus.METHOD_NOT_ALLOWED, ErrorCodes.BAD_REQUEST, ex.getMessage(), Map.of());
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiErrorResponse> handleMissingHeader(MissingRequestHeaderException ex) {
        return respond(HttpStatus.BAD_REQUEST, ErrorCodes.BAD_REQUEST,
                "Missing required header: " + ex.getHeaderName(), Map.of());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return respond(HttpStatus.BAD_REQUEST, ErrorCodes.BAD_REQUEST,
                "Invalid value for parameter '%s': %s".formatted(ex.getName(), ex.getValue()), Map.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleGeneral(Exception ex) {
        logger.error("INTERNAL_ERROR", ex);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCodes.INTERNAL_ERROR, "An unexpected error occurred",
                Map.of());
    }

    private static ResponseEntity<ApiErrorResponse> respond(HttpStatus status, String code, String message,
                                                            Map<String, Object> details) {
        String traceId = Span.current().getSpanContext().getTraceId();
        return ResponseEntity.status(status)
                .header("X-Trace-Id", traceId)
                .body(new ApiErrorResponse(new ApiError(code, message, details), traceId));
    }
}
