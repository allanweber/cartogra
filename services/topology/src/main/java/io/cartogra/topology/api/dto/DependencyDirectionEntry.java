package io.cartogra.topology.api.dto;

import io.cartogra.topology.domain.DependencyEdge;
import io.cartogra.topology.domain.DependencyProtocol;
import io.cartogra.topology.domain.GraphNode;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

public record DependencyDirectionEntry(
        UUID id,
        UUID serviceId,
        String name,
        @Nullable UUID teamId,
        @Nullable String tier,
        String healthStatus,
        DependencyProtocol protocol,
        @Nullable String metadata,
        Instant createdAt,
        Instant updatedAt
) {
    public static DependencyDirectionEntry from(DependencyEdge edge) {
        GraphNode counterpart = edge.counterpart();
        return new DependencyDirectionEntry(
                edge.id(), counterpart.serviceId(), counterpart.name(), counterpart.teamId(), counterpart.tier(),
                counterpart.healthStatus(), edge.protocol(), edge.metadata(), edge.createdAt(), edge.updatedAt());
    }
}
