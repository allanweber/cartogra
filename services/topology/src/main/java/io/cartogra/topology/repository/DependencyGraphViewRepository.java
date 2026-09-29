package io.cartogra.topology.repository;

import io.cartogra.topology.domain.BlastRadiusDirection;
import io.cartogra.topology.domain.DependencyType;
import io.cartogra.topology.domain.GraphEdge;
import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface DependencyGraphViewRepository {
    /** Runs {@code REFRESH MATERIALIZED VIEW CONCURRENTLY dependency_graph_edges}. */
    void refresh();

    /**
     * Edges from the (possibly stale) materialized view where {@code serviceIds} contains
     * either endpoint, optionally filtered by type. Reads the view as-is — no refresh is
     * triggered here.
     */
    List<GraphEdge> findByServiceIds(UUID tenantId, Collection<UUID> serviceIds, @Nullable DependencyType type);

    /**
     * Recursive traversal from {@code serviceId} following {@code direction}, up to
     * {@code maxDepth} hops plus one extra "peek" hop (rows with {@code depth == maxDepth + 1}
     * exist only so the caller can detect depth-cap truncation and must be excluded from any
     * response). Cycle-safe via a per-path visited array. Rows are grouped by service id
     * ({@code MIN(depth)} = shortest path), ordered by (depth, service_id) ascending.
     *
     * <p><b>Note on direction:</b> {@link BlastRadiusDirection#UPSTREAM} here means "what
     * {@code serviceId} depends on" (forward traversal, source -&gt; target) — the OPPOSITE of
     * {@link io.cartogra.topology.domain.DependencyService}'s "upstream".
     * {@link BlastRadiusDirection#DOWNSTREAM} means "who is impacted if {@code serviceId} fails"
     * (backward traversal, target -&gt; source) — the OPPOSITE of {@code DependencyService}'s
     * "downstream". See {@link io.cartogra.topology.domain.BlastRadiusService} Javadoc.
     */
    List<BlastRadiusRow> findBlastRadius(UUID tenantId, UUID serviceId, BlastRadiusDirection direction, int maxDepth);
}
