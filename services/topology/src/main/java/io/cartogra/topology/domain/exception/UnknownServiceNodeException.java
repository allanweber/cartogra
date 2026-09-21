package io.cartogra.topology.domain.exception;

import java.util.UUID;

public class UnknownServiceNodeException extends RuntimeException {
    public UnknownServiceNodeException(UUID serviceId) {
        super("Unknown or deleted service node: " + serviceId);
    }
}
