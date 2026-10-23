package io.cartogra.topology.domain.exception;

import java.util.UUID;

public class UnresolvedSpanServiceAlreadyResolvedException extends RuntimeException {
    public UnresolvedSpanServiceAlreadyResolvedException(UUID id) {
        super("Unresolved span service already resolved: " + id);
    }
}
