package io.cartogra.topology.domain;

import org.jspecify.annotations.Nullable;

import java.util.UUID;

public record GraphEdge(
        UUID tenantId,
        UUID sourceServiceId,
        UUID targetServiceId,
        DependencyType type,
        DependencyProtocol protocol,
        @Nullable String metadata
) {
}
