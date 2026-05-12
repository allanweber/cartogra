package io.cartogra.registry.domain.exception;

public class DuplicateServiceNameException extends RuntimeException {
    public DuplicateServiceNameException(String name) {
        super("A service named '" + name + "' already exists in this tenant");
    }
}
