package io.cartogra.contract.domain.exception;

/** The service of a CI check is unknown, deleted, or in another tenant. */
public class CheckServiceNotFoundException extends RuntimeException {
    public CheckServiceNotFoundException(String reference) {
        super("Service not found: " + reference);
    }
}
