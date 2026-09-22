package io.cartogra.topology.api.dto;

import io.cartogra.topology.domain.GraphNode;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

public record GraphNodeResponse(
        UUID serviceId,
        String name,
        @Nullable UUID teamId,
        @Nullable String tier,
        String healthStatus
) {
    public static GraphNodeResponse from(GraphNode node) {
        return new GraphNodeResponse(node.serviceId(), node.name(), node.teamId(), node.tier(), node.healthStatus());
    }
}
