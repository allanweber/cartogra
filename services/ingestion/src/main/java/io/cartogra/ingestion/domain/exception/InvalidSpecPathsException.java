package io.cartogra.ingestion.domain.exception;

import java.util.Map;

/** A custom spec path list that breaks a validation rule. The details map each bad path to its reason. */
public class InvalidSpecPathsException extends RuntimeException {

    private final Map<String, Object> details;

    public InvalidSpecPathsException(String message, Map<String, Object> details) {
        super(message);
        this.details = Map.copyOf(details);
    }

    public Map<String, Object> details() {
        return details;
    }
}
