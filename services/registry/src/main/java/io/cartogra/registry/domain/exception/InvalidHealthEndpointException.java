package io.cartogra.registry.domain.exception;

public class InvalidHealthEndpointException extends RuntimeException {

    private final String offendingUrl;

    public InvalidHealthEndpointException(String offendingUrl, String reason) {
        super("Invalid health endpoint '" + offendingUrl + "': " + reason);
        this.offendingUrl = offendingUrl;
    }

    public String offendingUrl() {
        return offendingUrl;
    }
}
