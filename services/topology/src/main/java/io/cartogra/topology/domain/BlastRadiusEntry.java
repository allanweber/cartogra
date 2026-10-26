package io.cartogra.topology.domain;

import java.util.List;

/**
 * A service reached by the traversal. {@code via} holds the distinct connections from the previous
 * hop (the selected service at distance 1) that reach it along a shortest path.
 */
public record BlastRadiusEntry(GraphNode node, int distance, List<BlastRadiusConnection> via) {
}
