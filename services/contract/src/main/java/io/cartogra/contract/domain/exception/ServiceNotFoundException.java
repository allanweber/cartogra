package io.cartogra.contract.domain.exception;

import java.util.UUID;

public class ServiceNotFoundException extends RuntimeException {
    public ServiceNotFoundException(UUID serviceId) {
        super("Service not found: " + serviceId);
    }
}
