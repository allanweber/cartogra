package io.cartogra.topology.api.dto;

import io.cartogra.topology.domain.DependencyProtocol;
import io.cartogra.topology.domain.DependencyType;
import io.cartogra.topology.domain.GraphEdge;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

public record GraphEdgeResponse(
        UUID source,
        UUID target,
        DependencyType dependencyType,
        DependencyProtocol protocol,
        @Nullable String metadata
) {
    public static GraphEdgeResponse from(GraphEdge edge) {
        return new GraphEdgeResponse(edge.sourceServiceId(), edge.targetServiceId(), edge.type(), edge.protocol(), edge.metadata());
    }
}
