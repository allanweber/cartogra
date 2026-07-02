package io.cartogra.registry.api.dto;

import java.util.Map;
import java.util.UUID;

public record ConnectionServiceCountsResponse(Map<UUID, Long> counts) {

    public static ConnectionServiceCountsResponse from(Map<UUID, Long> counts) {
        return new ConnectionServiceCountsResponse(counts);
    }
}
