package io.cartogra.ingestion.domain.exception;

import java.util.UUID;

public class WebhookSignatureInvalidException extends RuntimeException {
    public WebhookSignatureInvalidException(UUID connectionId) {
        super("Webhook signature verification failed for connection: " + connectionId);
    }
}
