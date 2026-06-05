package io.cartogra.registry.application.port;

import io.cartogra.registry.domain.exception.InvalidHealthEndpointException;

public interface HealthEndpointValidator {
    /**
     * Validates that the given URL is safe to use as a health probe target.
     * Throws {@link InvalidHealthEndpointException} if the URL is rejected.
     */
    void validate(String url);
}
