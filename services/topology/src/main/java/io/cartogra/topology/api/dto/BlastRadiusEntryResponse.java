package io.cartogra.topology.api.dto;

import io.cartogra.topology.domain.BlastRadiusEntry;
import io.cartogra.topology.domain.GraphNode;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

public record BlastRadiusEntryResponse(
        UUID serviceId,
        String name,
        @Nullable UUID teamId,
        @Nullable String tier,
        String healthStatus,
        int distance
) {
    public static BlastRadiusEntryResponse from(BlastRadiusEntry entry) {
        GraphNode node = entry.node();
        return new BlastRadiusEntryResponse(node.serviceId(), node.name(), node.teamId(), node.tier(),
                node.healthStatus(), entry.distance());
    }
}
