package io.cartogra.topology.domain.exception;

import java.util.UUID;

public class DriftNoLongerObservedException extends RuntimeException {
    public DriftNoLongerObservedException(UUID id) {
        super("Drift " + id + " has no live observed dependency left to accept");
    }
}
