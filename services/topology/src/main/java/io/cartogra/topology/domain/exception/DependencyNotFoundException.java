package io.cartogra.topology.domain.exception;

import java.util.UUID;

public class DependencyNotFoundException extends RuntimeException {
    public DependencyNotFoundException(UUID id) {
        super("Dependency not found: " + id);
    }
}
