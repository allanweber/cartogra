package io.cartogra.topology.domain.exception;

import java.util.UUID;

public class UnresolvedSpanServiceNotFoundException extends RuntimeException {
    public UnresolvedSpanServiceNotFoundException(UUID id) {
        super("Unresolved span service not found: " + id);
    }
}
