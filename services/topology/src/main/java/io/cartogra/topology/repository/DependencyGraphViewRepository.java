package io.cartogra.topology.repository;

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
}
