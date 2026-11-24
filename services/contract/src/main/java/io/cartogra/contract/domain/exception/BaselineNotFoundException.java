package io.cartogra.contract.domain.exception;

import java.util.UUID;

public class BaselineNotFoundException extends RuntimeException {
    public BaselineNotFoundException(UUID serviceId) {
        super("Service " + serviceId + " has no contract version to check against");
    }
}
