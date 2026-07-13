package io.cartogra.gateway.domain.exception;

public class PlanLimitExceededException extends RuntimeException {
    public PlanLimitExceededException(String resource, int limit) {
        super("Plan limit reached: %s (max %d)".formatted(resource, limit));
    }
}
