package io.cartogra.topology.domain;

import io.cartogra.topology.domain.exception.UnknownServiceNodeException;
import io.cartogra.topology.repository.BlastRadiusRow;
import io.cartogra.topology.repository.DependencyGraphViewRepository;
import io.cartogra.topology.repository.GraphNodeRepository;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Computes a service's blast radius — the set of services reachable from it within a bounded
 * number of hops, in both directions.
 *
 * <p><b>Direction naming is the OPPOSITE of {@link DependencyService}'s upstream/downstream.</b>
 * That feature's {@code downstream} is "this service depends on" and {@code upstream} is
 * "depends on this service". Here:
 * <ul>
 *   <li>{@link BlastRadiusDirection#UPSTREAM} = "what this service depends on" (forward
 *       traversal, same direction as {@code DependencyService}'s {@code downstream}).</li>
 *   <li>{@link BlastRadiusDirection#DOWNSTREAM} = "who is impacted if this service fails"
 *       (backward traversal, same direction as {@code DependencyService}'s {@code upstream}).</li>
 * </ul>
 * Both features are independently correct per their own specs — they just reuse the same two
 * words for opposite directions. Do not "fix" this by copying logic between the two services.
 */
@org.springframework.stereotype.Service
public class BlastRadiusService {

    public static final int DEFAULT_DEPTH = 3;
    public static final int MAX_DEPTH = 6;
    public static final int MAX_NODES_PER_DIRECTION = 200;

    private final GraphNodeRepository graphNodeRepository;
    private final DependencyGraphViewRepository graphViewRepository;

    public BlastRadiusService(GraphNodeRepository graphNodeRepository, DependencyGraphViewRepository graphViewRepository) {
        this.graphNodeRepository = graphNodeRepository;
        this.graphViewRepository = graphViewRepository;
    }

    public BlastRadius compute(UUID tenantId, UUID serviceId, @Nullable BlastRadiusDirection directionFilter,
            @Nullable Integer requestedDepth) {
        requireLiveNode(tenantId, serviceId);
        int maxDepth = clampDepth(requestedDepth);

        BlastRadiusDirectionResult upstream = (directionFilter == null || directionFilter == BlastRadiusDirection.UPSTREAM)
                ? traverse(tenantId, serviceId, BlastRadiusDirection.UPSTREAM, maxDepth)
                : BlastRadiusDirectionResult.empty();
        BlastRadiusDirectionResult downstream = (directionFilter == null || directionFilter == BlastRadiusDirection.DOWNSTREAM)
                ? traverse(tenantId, serviceId, BlastRadiusDirection.DOWNSTREAM, maxDepth)
                : BlastRadiusDirectionResult.empty();

        return new BlastRadius(serviceId, upstream, downstream, maxDepth);
    }

    private BlastRadiusDirectionResult traverse(UUID tenantId, UUID serviceId, BlastRadiusDirection direction, int maxDepth) {
        List<BlastRadiusRow> rows = graphViewRepository.findBlastRadius(tenantId, serviceId, direction, maxDepth);

        int nodesBeyondDepth = (int) rows.stream().filter(row -> row.depth() == maxDepth + 1).count();
        List<BlastRadiusRow> withinDepth = rows.stream().filter(row -> row.depth() <= maxDepth).toList();

        Set<UUID> ids = withinDepth.stream().map(BlastRadiusRow::serviceId).collect(Collectors.toCollection(LinkedHashSet::new));
        Map<UUID, GraphNode> nodesById = graphNodeRepository.findByServiceIds(tenantId, ids, ids.size()).stream()
                .collect(Collectors.toMap(GraphNode::serviceId, Function.identity()));

        List<BlastRadiusEntry> live = withinDepth.stream()
                .filter(row -> nodesById.get(row.serviceId()) != null)
                .map(row -> new BlastRadiusEntry(nodesById.get(row.serviceId()), row.depth()))
                .toList();

        boolean nodeCapTruncated = live.size() > MAX_NODES_PER_DIRECTION;
        int nodesBeyondCap = nodeCapTruncated ? live.size() - MAX_NODES_PER_DIRECTION : 0;
        List<BlastRadiusEntry> entries = nodeCapTruncated ? live.subList(0, MAX_NODES_PER_DIRECTION) : live;

        return new BlastRadiusDirectionResult(entries, nodesBeyondDepth > 0, nodesBeyondDepth, nodeCapTruncated, nodesBeyondCap);
    }

    private int clampDepth(@Nullable Integer requested) {
        if (requested != null && requested < 1) {
            throw new IllegalArgumentException("depth must be at least 1");
        }
        return Math.min(requested == null ? DEFAULT_DEPTH : requested, MAX_DEPTH);
    }

    private void requireLiveNode(UUID tenantId, UUID serviceId) {
        GraphNode node = graphNodeRepository.findByServiceId(tenantId, serviceId)
                .orElseThrow(() -> new UnknownServiceNodeException(serviceId));
        if (node.isDeleted()) {
            throw new UnknownServiceNodeException(serviceId);
        }
    }
}
