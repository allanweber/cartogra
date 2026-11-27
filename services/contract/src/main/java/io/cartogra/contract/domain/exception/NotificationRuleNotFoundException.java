package io.cartogra.contract.domain.exception;

import java.util.UUID;

public class NotificationRuleNotFoundException extends RuntimeException {

    public NotificationRuleNotFoundException(UUID id) {
        super("Notification rule with id " + id + " not found");
    }
}
