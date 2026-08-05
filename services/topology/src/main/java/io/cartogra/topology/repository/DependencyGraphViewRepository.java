package io.cartogra.topology.repository;

public interface DependencyGraphViewRepository {
    /** Runs {@code REFRESH MATERIALIZED VIEW CONCURRENTLY dependency_graph_edges}. */
    void refresh();
}
