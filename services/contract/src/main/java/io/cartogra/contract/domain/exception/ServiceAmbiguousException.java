package io.cartogra.contract.domain.exception;

public class ServiceAmbiguousException extends RuntimeException {
    public ServiceAmbiguousException(String name) {
        super("More than one service has the name '" + name + "'. Send the service id.");
    }
}
