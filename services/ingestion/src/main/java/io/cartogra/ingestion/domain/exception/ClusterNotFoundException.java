package io.cartogra.ingestion.domain.exception;

import java.util.UUID;

public class ClusterNotFoundException extends RuntimeException {
    public ClusterNotFoundException(UUID id) {
        super("Kubernetes cluster with id " + id + " not found");
    }
}
