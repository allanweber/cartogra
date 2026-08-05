package io.cartogra.topology.infrastructure.jdbc;

import io.cartogra.topology.repository.DependencyGraphViewRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcDependencyGraphViewRepository implements DependencyGraphViewRepository {

    private final JdbcTemplate jdbc;

    public JdbcDependencyGraphViewRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void refresh() {
        jdbc.execute("REFRESH MATERIALIZED VIEW CONCURRENTLY dependency_graph_edges");
    }
}
