package io.cartogra.contract.domain.exception;

public class RegistryUnavailableException extends RuntimeException {
    public RegistryUnavailableException(Throwable cause) {
        super("Registry is unavailable; cannot confirm the service exists", cause);
    }
}
