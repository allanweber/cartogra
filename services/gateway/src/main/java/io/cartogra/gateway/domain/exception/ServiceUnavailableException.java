package io.cartogra.gateway.domain.exception;

public class ServiceUnavailableException extends RuntimeException {

    private final String serviceName;

    public ServiceUnavailableException(String serviceName) {
        super("Downstream service '%s' is unavailable (circuit breaker open)".formatted(serviceName));
        this.serviceName = serviceName;
    }

    public String serviceName() {
        return serviceName;
    }
}
