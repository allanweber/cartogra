package io.cartogra.contract.domain.exception;

import java.util.List;

public class InvalidSpecException extends RuntimeException {

    private final List<String> messages;

    public InvalidSpecException(List<String> messages) {
        super("Spec is not a valid OpenAPI 3.x document");
        this.messages = List.copyOf(messages);
    }

    public List<String> messages() {
        return messages;
    }
}
