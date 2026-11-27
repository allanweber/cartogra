package io.cartogra.contract.domain.exception;

import java.util.UUID;

public class NotificationRuleDisabledException extends RuntimeException {

    public NotificationRuleDisabledException(UUID id) {
        super("Notification rule " + id + " is disabled");
    }
}
