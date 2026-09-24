package io.cartogra.topology.domain.exception;

public class DuplicateDependencyException extends RuntimeException {
    public DuplicateDependencyException(String sourceServiceName, String targetServiceName) {
        super("A declared dependency already exists from " + sourceServiceName + " to " + targetServiceName
                + " with this protocol");
    }
}
