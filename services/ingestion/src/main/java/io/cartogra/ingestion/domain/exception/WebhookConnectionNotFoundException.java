package io.cartogra.ingestion.domain.exception;

public class WebhookConnectionNotFoundException extends RuntimeException {
    public WebhookConnectionNotFoundException(String identifier) {
        super("No webhook-enabled connection found for: " + identifier);
    }
}
