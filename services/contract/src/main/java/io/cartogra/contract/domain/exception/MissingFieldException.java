package io.cartogra.contract.domain.exception;

public class MissingFieldException extends RuntimeException {

    private final String field;

    public MissingFieldException(String field) {
        super(field + " is required");
        this.field = field;
    }

    public String field() {
        return field;
    }
}
