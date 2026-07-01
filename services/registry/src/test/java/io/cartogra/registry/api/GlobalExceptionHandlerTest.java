package io.cartogra.registry.api;

import io.cartogra.common.api.ErrorCodes;
import io.cartogra.registry.domain.exception.DuplicateServiceNameException;
import io.cartogra.registry.domain.exception.DuplicateTeamNameException;
import io.cartogra.registry.domain.exception.InvalidHealthEndpointException;
import io.cartogra.registry.domain.exception.ServiceNotFoundException;
import io.cartogra.registry.domain.exception.TeamNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.NoSuchElementException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void serviceNotFoundReturns404() {
        var resp = handler.handleServiceNotFound(new ServiceNotFoundException(UUID.randomUUID()));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(error(resp)).isEqualTo(ErrorCodes.NOT_FOUND);
        assertThat(traceId(resp)).isNotNull();
    }

    @Test
    void teamNotFoundReturns404() {
        var resp = handler.handleTeamNotFound(new TeamNotFoundException(UUID.randomUUID()));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(error(resp)).isEqualTo(ErrorCodes.NOT_FOUND);
    }

    @Test
    void invalidHealthEndpointReturns422() {
        var resp = handler.handleInvalidHealthEndpoint(new InvalidHealthEndpointException("bad url", "not allowed"));

        assertThat(resp.getStatusCode().value()).isEqualTo(422);
        assertThat(error(resp)).isEqualTo(ErrorCodes.INVALID_HEALTH_ENDPOINT);
    }

    @Test
    void duplicateServiceNameReturns409() {
        var resp = handler.handleDuplicateServiceName(new DuplicateServiceNameException("svc"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(error(resp)).isEqualTo(ErrorCodes.CONFLICT);
    }

    @Test
    void duplicateTeamNameReturns409() {
        var resp = handler.handleDuplicateTeamName(new DuplicateTeamNameException("team"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(error(resp)).isEqualTo(ErrorCodes.CONFLICT);
    }

    @Test
    void duplicateKeyReturns409() {
        var resp = handler.handleDuplicateKey(new DuplicateKeyException("unique violation"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(error(resp)).isEqualTo(ErrorCodes.CONFLICT);
    }

    @Test
    void noSuchElementReturns404() {
        var resp = handler.handleNotFound(new NoSuchElementException("missing"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(error(resp)).isEqualTo(ErrorCodes.NOT_FOUND);
    }

    @Test
    void illegalArgumentReturns400() {
        var resp = handler.handleIllegalArgument(new IllegalArgumentException("bad value"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(error(resp)).isEqualTo(ErrorCodes.BAD_REQUEST);
    }

    @Test
    void noHandlerFoundReturns404() {
        var ex = new NoHandlerFoundException("GET", "/unknown", null);
        var resp = handler.handleNoHandler(ex);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(error(resp)).isEqualTo(ErrorCodes.NOT_FOUND);
        assertThat(resp.getBody().error().message()).contains("/unknown");
    }

    @Test
    void methodNotSupportedReturns405() {
        var ex = new HttpRequestMethodNotSupportedException("DELETE");
        var resp = handler.handleMethodNotSupported(ex);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(error(resp)).isEqualTo(ErrorCodes.BAD_REQUEST);
    }

    @Test
    void missingHeaderReturns400WithHeaderName() {
        MissingRequestHeaderException ex = org.mockito.Mockito.mock(MissingRequestHeaderException.class);
        org.mockito.Mockito.when(ex.getHeaderName()).thenReturn("X-Tenant-Id");
        var resp = handler.handleMissingHeader(ex);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(error(resp)).isEqualTo(ErrorCodes.BAD_REQUEST);
        assertThat(resp.getBody().error().message()).contains("X-Tenant-Id");
    }

    @Test
    void malformedBodyReturns400() {
        var ex = new HttpMessageNotReadableException("unreadable", (org.springframework.http.HttpInputMessage) null);
        var resp = handler.handleMessageNotReadable(ex);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(error(resp)).isEqualTo(ErrorCodes.VALIDATION_ERROR);
        assertThat(resp.getBody().error().message()).isEqualTo("Malformed or unreadable request body");
    }

    @Test
    void genericExceptionReturns500WithoutDetails() {
        var resp = handler.handleGeneral(new RuntimeException("internal failure"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(error(resp)).isEqualTo(ErrorCodes.INTERNAL_ERROR);
        assertThat(resp.getBody().error().message()).doesNotContain("internal failure");
    }

    @Test
    void allHandlersIncludeXTraceIdHeader() {
        assertThat(traceId(handler.handleServiceNotFound(new ServiceNotFoundException(UUID.randomUUID())))).isNotNull();
        assertThat(traceId(handler.handleTeamNotFound(new TeamNotFoundException(UUID.randomUUID())))).isNotNull();
        assertThat(traceId(handler.handleDuplicateServiceName(new DuplicateServiceNameException("x")))).isNotNull();
        assertThat(traceId(handler.handleGeneral(new RuntimeException()))).isNotNull();
    }

    private String error(ResponseEntity<?> resp) {
        var body = (io.cartogra.common.api.ApiErrorResponse) resp.getBody();
        return body.error().code();
    }

    private String traceId(ResponseEntity<?> resp) {
        return resp.getHeaders().getFirst("X-Trace-Id");
    }
}
