package io.cartogra.ingestion.domain.exception;

import java.util.UUID;

public class WebhookSignatureInvalidException extends RuntimeException {
    public WebhookSignatureInvalidException(UUID connectionId) {
        super("Invalid webhook signature for connection: " + connectionId);
    }
}
