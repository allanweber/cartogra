package io.cartogra.registry.domain.exception;

public class DuplicateTeamNameException extends RuntimeException {
    public DuplicateTeamNameException(String name) {
        super("A team named '" + name + "' already exists in this tenant");
    }
}
