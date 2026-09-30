package io.cartogra.topology.domain;

public record Spof(GraphNode node, int fanIn, RiskSeverity severity) {
}
