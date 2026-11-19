package io.cartogra.contract.domain.exception;

public class InvalidParameterException extends RuntimeException {

    private final String parameter;
    private final String reason;

    public InvalidParameterException(String parameter, String reason) {
        super(parameter + " " + reason);
        this.parameter = parameter;
        this.reason = reason;
    }

    public String parameter() {
        return parameter;
    }

    public String reason() {
        return reason;
    }
}
