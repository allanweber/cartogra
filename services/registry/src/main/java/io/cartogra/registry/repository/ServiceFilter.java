package io.cartogra.registry.repository;

import java.util.List;
import java.util.UUID;

public record ServiceFilter(
        UUID teamId,
        boolean unowned,
        String healthStatus,
        List<String> techStackQuery,
        String search,
        String source
) {
    public static ServiceFilter empty() {
        return new ServiceFilter(null, false, null, null, null, null);
    }
}
