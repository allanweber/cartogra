package io.cartogra.topology.api.dto;

import io.cartogra.topology.domain.GraphNode;
import io.cartogra.topology.domain.Spof;
import org.jspecify.annotations.Nullable;

import java.util.Locale;
import java.util.UUID;

public record SpofResponse(
        UUID serviceId,
        String name,
        @Nullable UUID teamId,
        @Nullable String tier,
        String healthStatus,
        int fanIn,
        String severity
) {
    public static SpofResponse from(Spof spof) {
        GraphNode node = spof.node();
        return new SpofResponse(node.serviceId(), node.name(), node.teamId(), node.tier(), node.healthStatus(),
                spof.fanIn(), spof.severity().name().toLowerCase(Locale.ROOT));
    }
}
