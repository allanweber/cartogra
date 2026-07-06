package io.cartogra.ingestion.api;

import io.cartogra.common.api.ErrorCodes;
import io.cartogra.ingestion.domain.exception.ClusterNotFoundException;
import io.cartogra.ingestion.domain.exception.ScmConnectionNotFoundException;
import io.cartogra.ingestion.domain.exception.WebhookConnectionNotFoundException;
import io.cartogra.ingestion.domain.exception.WebhookSignatureInvalidException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void clusterNotFoundReturns404() {
        var resp = handler.handleClusterNotFound(new ClusterNotFoundException(UUID.randomUUID()));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(error(resp)).isEqualTo(ErrorCodes.NOT_FOUND);
        assertThat(traceId(resp)).isNotNull();
    }

    @Test
    void scmConnectionNotFoundReturns404() {
        var resp = handler.handleScmConnectionNotFound(new ScmConnectionNotFoundException(UUID.randomUUID()));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(error(resp)).isEqualTo(ErrorCodes.NOT_FOUND);
        assertThat(traceId(resp)).isNotNull();
    }

    @Test
    void webhookConnectionNotFoundReturns404() {
        var resp = handler.handleWebhookConnectionNotFound(new WebhookConnectionNotFoundException("repo/owner"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(error(resp)).isEqualTo(ErrorCodes.WEBHOOK_CONNECTION_NOT_FOUND);
    }

    @Test
    void webhookSignatureInvalidReturns401() {
        var resp = handler.handleWebhookSignatureInvalid(new WebhookSignatureInvalidException(UUID.randomUUID()));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(error(resp)).isEqualTo(ErrorCodes.WEBHOOK_SIGNATURE_INVALID);
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
    void illegalArgumentReturns400() {
        var resp = handler.handleIllegalArgument(new IllegalArgumentException("bad value"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(error(resp)).isEqualTo(ErrorCodes.BAD_REQUEST);
    }

    @Test
    void genericExceptionReturns500WithoutDetails() {
        var resp = handler.handleGeneral(new RuntimeException("internal"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(error(resp)).isEqualTo(ErrorCodes.INTERNAL_ERROR);
        assertThat(resp.getBody().error().message()).doesNotContain("internal");
    }

    @Test
    void allHandlersIncludeXTraceIdHeader() {
        assertThat(traceId(handler.handleScmConnectionNotFound(new ScmConnectionNotFoundException(UUID.randomUUID())))).isNotNull();
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
