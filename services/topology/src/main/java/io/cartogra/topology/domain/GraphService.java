package io.cartogra.topology.domain;

import io.cartogra.topology.repository.DependencyGraphViewRepository;
import io.cartogra.topology.repository.GraphNodeRepository;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Reads a tenant's dependency graph for {@code GET /graph}. A cross-team neighbor node can be
 * pulled in alongside {@code teamId}'s own nodes, sharing the same node-cap budget, so that no
 * returned edge ever dangles.
 */
@org.springframework.stereotype.Service
public class GraphService {

    public static final int MAX_NODES = 500;

    private final GraphNodeRepository graphNodeRepository;
    private final DependencyGraphViewRepository dependencyGraphViewRepository;

    public GraphService(GraphNodeRepository graphNodeRepository,
            DependencyGraphViewRepository dependencyGraphViewRepository) {
        this.graphNodeRepository = graphNodeRepository;
        this.dependencyGraphViewRepository = dependencyGraphViewRepository;
    }

    public Graph read(UUID tenantId, @Nullable UUID teamId, @Nullable DependencyType type, @Nullable Integer requestedLimit) {
        if (requestedLimit != null && requestedLimit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
        int limit = requestedLimit == null ? MAX_NODES : Math.min(requestedLimit, MAX_NODES);

        Capped<GraphNode> primary = cap(graphNodeRepository.findForGraph(tenantId, teamId, limit + 1), limit);
        List<GraphNode> primaryNodes = primary.items();
        boolean truncated = primary.truncated();
        if (primaryNodes.isEmpty()) {
            return new Graph(List.of(), List.of(), truncated);
        }

        Set<UUID> primaryServiceIds = primaryNodes.stream()
                .map(GraphNode::serviceId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<GraphEdge> edgesTouchingPrimary = dependencyGraphViewRepository.findByServiceIds(tenantId, primaryServiceIds, type);

        List<GraphNode> neighborNodes = List.of();
        if (!truncated) {
            Set<UUID> neighborServiceIds = new LinkedHashSet<>();
            for (GraphEdge edge : edgesTouchingPrimary) {
                if (!primaryServiceIds.contains(edge.sourceServiceId())) {
                    neighborServiceIds.add(edge.sourceServiceId());
                }
                if (!primaryServiceIds.contains(edge.targetServiceId())) {
                    neighborServiceIds.add(edge.targetServiceId());
                }
            }
            if (!neighborServiceIds.isEmpty()) {
                int remainingBudget = limit - primaryNodes.size();
                if (remainingBudget <= 0) {
                    truncated = true;
                } else {
                    Capped<GraphNode> neighbors = cap(
                            graphNodeRepository.findByServiceIds(tenantId, neighborServiceIds, remainingBudget + 1),
                            remainingBudget);
                    neighborNodes = neighbors.items();
                    truncated = neighbors.truncated();
                }
            }
        }

        List<GraphNode> allNodes = new ArrayList<>(primaryNodes);
        allNodes.addAll(neighborNodes);
        allNodes.sort(Comparator.comparing(GraphNode::name).thenComparing(GraphNode::serviceId));
        Set<UUID> finalServiceIds = allNodes.stream().map(GraphNode::serviceId).collect(Collectors.toSet());

        List<GraphEdge> finalEdges = edgesTouchingPrimary.stream()
                .filter(edge -> finalServiceIds.contains(edge.sourceServiceId()) && finalServiceIds.contains(edge.targetServiceId()))
                .toList();

        return new Graph(allNodes, finalEdges, truncated);
    }

    /** Caller fetches {@code limit + 1} rows; this trims back to {@code limit} and flags whether that extra row existed. */
    private static <T> Capped<T> cap(List<T> fetched, int limit) {
        if (fetched.size() > limit) {
            return new Capped<>(fetched.subList(0, limit), true);
        }
        return new Capped<>(fetched, false);
    }

    private record Capped<T>(List<T> items, boolean truncated) {
    }
}
