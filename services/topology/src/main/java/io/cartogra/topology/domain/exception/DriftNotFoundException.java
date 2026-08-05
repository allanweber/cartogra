package io.cartogra.topology.domain.exception;

import java.util.UUID;

public class DriftNotFoundException extends RuntimeException {
    public DriftNotFoundException(UUID id) {
        super("Drift record not found: " + id);
    }
}
