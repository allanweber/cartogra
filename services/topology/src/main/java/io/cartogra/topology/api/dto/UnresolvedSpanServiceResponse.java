package io.cartogra.topology.api.dto;

import io.cartogra.topology.domain.UnresolvedResolution;
import io.cartogra.topology.domain.UnresolvedSpanService;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

public record UnresolvedSpanServiceResponse(
        UUID id,
        String name,
        Instant firstSeenAt,
        Instant lastSeenAt,
        long seenCount,
        @Nullable Instant resolvedAt,
        @Nullable UnresolvedResolution resolution
) {
    public static UnresolvedSpanServiceResponse from(UnresolvedSpanService service) {
        return new UnresolvedSpanServiceResponse(service.id(), service.name(), service.firstSeenAt(),
                service.lastSeenAt(), service.seenCount(), service.deletedAt(), service.resolution());
    }
}
