package io.cartogra.ingestion.domain.exception;

import java.util.UUID;

public class ScmConnectionNotFoundException extends RuntimeException {
    public ScmConnectionNotFoundException(UUID id) {
        super("SCM connection not found: " + id);
    }
}
