package io.cartogra.gateway.domain.exception;

public class UnverifiedEmailException extends RuntimeException {
    public UnverifiedEmailException() {
        super("Email address has not been verified");
    }
}
