package io.cartogra.gateway.domain.exception;

public class InvalidOAuthStateException extends RuntimeException {
    public InvalidOAuthStateException(String message) {
        super(message);
    }
}
