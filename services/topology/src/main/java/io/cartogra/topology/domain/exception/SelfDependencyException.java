package io.cartogra.topology.domain.exception;

import java.util.UUID;

public class SelfDependencyException extends RuntimeException {
    public SelfDependencyException(UUID serviceId) {
        super("A service cannot depend on itself: " + serviceId);
    }
}
