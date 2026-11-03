package io.cartogra.gateway.domain.exception;

public class SignupDisabledException extends RuntimeException {
    public SignupDisabledException() {
        super("Sign-up is disabled on this instance");
    }
}
