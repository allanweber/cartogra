package io.cartogra.topology.domain.exception;

import java.util.UUID;

public class DuplicateDependencyException extends RuntimeException {
    public DuplicateDependencyException(UUID sourceServiceId, UUID targetServiceId) {
        super("A declared dependency already exists from " + sourceServiceId + " to " + targetServiceId
                + " with this protocol");
    }
}
