package io.cartogra.topology.api.dto;

import io.cartogra.topology.domain.Graph;

import java.util.List;

public record GraphResponse(
        List<GraphNodeResponse> nodes,
        List<GraphEdgeResponse> edges,
        boolean truncated
) {
    public static GraphResponse from(Graph graph) {
        return new GraphResponse(
                graph.nodes().stream().map(GraphNodeResponse::from).toList(),
                graph.edges().stream().map(GraphEdgeResponse::from).toList(),
                graph.truncated());
    }
}
