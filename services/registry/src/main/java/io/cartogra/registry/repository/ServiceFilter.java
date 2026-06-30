package io.cartogra.registry.repository;

import java.util.List;
import java.util.UUID;

public record ServiceFilter(
        UUID teamId,
        String healthStatus,
        List<String> techStackQuery,
        String search,
        String source
) {
    public static ServiceFilter empty() {
        return new ServiceFilter(null, null, null, null, null);
    }
}
