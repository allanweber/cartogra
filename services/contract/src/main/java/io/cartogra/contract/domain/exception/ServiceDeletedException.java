package io.cartogra.contract.domain.exception;

import java.util.UUID;

public class ServiceDeletedException extends RuntimeException {
    public ServiceDeletedException(UUID serviceId) {
        super("Service not found: " + serviceId);
    }
}
