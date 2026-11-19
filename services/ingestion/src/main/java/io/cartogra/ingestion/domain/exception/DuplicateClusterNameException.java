package io.cartogra.ingestion.domain.exception;

public class DuplicateClusterNameException extends RuntimeException {
    public DuplicateClusterNameException(String name) {
        super("A Kubernetes cluster with name '" + name + "' already exists");
    }
}
