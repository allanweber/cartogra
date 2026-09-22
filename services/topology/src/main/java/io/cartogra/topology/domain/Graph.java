package io.cartogra.topology.domain;

import java.util.List;

public record Graph(List<GraphNode> nodes, List<GraphEdge> edges, boolean truncated) {
}
