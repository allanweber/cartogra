package io.cartogra.registry.repository;

import java.util.UUID;

public record ServiceFilter(
        UUID teamId,
        String healthStatus,
        String techStackQuery,
        String search
) {
    public static ServiceFilter empty() {
        return new ServiceFilter(null, null, null, null);
    }
}
