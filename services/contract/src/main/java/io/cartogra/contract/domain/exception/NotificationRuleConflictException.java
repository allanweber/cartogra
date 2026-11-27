package io.cartogra.contract.domain.exception;

public class NotificationRuleConflictException extends RuntimeException {

    public NotificationRuleConflictException() {
        super("An active notification rule already exists for this scope");
    }
}
