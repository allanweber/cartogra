package io.cartogra.ingestion.domain.exception;

public class WebhookConnectionNotFoundException extends RuntimeException {
    public WebhookConnectionNotFoundException(String identifier) {
        super("No webhook registration found for: " + identifier);
    }
}
