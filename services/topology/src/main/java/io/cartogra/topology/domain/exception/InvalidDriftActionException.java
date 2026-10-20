package io.cartogra.topology.domain.exception;

import io.cartogra.topology.domain.DriftAction;
import io.cartogra.topology.domain.DriftType;

public class InvalidDriftActionException extends RuntimeException {
    public InvalidDriftActionException(DriftAction action, DriftType type) {
        super("Action " + action + " does not apply to " + type + " drift");
    }
}
