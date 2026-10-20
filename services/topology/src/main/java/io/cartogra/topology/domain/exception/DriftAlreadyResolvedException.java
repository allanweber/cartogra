package io.cartogra.topology.domain.exception;

import java.util.UUID;

public class DriftAlreadyResolvedException extends RuntimeException {
    public DriftAlreadyResolvedException(UUID id) {
        super("Drift record already resolved: " + id);
    }
}
