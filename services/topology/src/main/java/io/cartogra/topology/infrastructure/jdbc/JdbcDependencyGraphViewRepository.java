package io.cartogra.topology.infrastructure.jdbc;

import io.cartogra.topology.domain.DependencyProtocol;
import io.cartogra.topology.domain.DependencyType;
import io.cartogra.topology.domain.GraphEdge;
import io.cartogra.topology.repository.DependencyGraphViewRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public class JdbcDependencyGraphViewRepository implements DependencyGraphViewRepository {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;

    public JdbcDependencyGraphViewRepository(JdbcTemplate jdbc, NamedParameterJdbcTemplate namedJdbc) {
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
    }

    @Override
    public void refresh() {
        jdbc.execute("REFRESH MATERIALIZED VIEW CONCURRENTLY dependency_graph_edges");
    }

    @Override
    public List<GraphEdge> findByServiceIds(UUID tenantId, Collection<UUID> serviceIds, @Nullable DependencyType type) {
        if (serviceIds.isEmpty()) {
            return List.of();
        }
        var sql = new StringBuilder("""
                SELECT * FROM dependency_graph_edges
                WHERE tenant_id = :tenantId
                  AND (source_service_id IN (:serviceIds) OR target_service_id IN (:serviceIds))
                """);
        var params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("serviceIds", serviceIds);
        if (type != null) {
            sql.append(" AND dependency_type = :type");
            params.addValue("type", type.toDbValue());
        }
        return namedJdbc.query(sql.toString(), params, GRAPH_EDGE_MAPPER);
    }

    private static final RowMapper<GraphEdge> GRAPH_EDGE_MAPPER = (rs, _) -> mapGraphEdge(rs);

    private static GraphEdge mapGraphEdge(ResultSet rs) throws SQLException {
        return new GraphEdge(
                UUID.fromString(rs.getString("tenant_id")),
                UUID.fromString(rs.getString("source_service_id")),
                UUID.fromString(rs.getString("target_service_id")),
                DependencyType.fromDbValue(rs.getString("dependency_type")),
                DependencyProtocol.fromDbValue(rs.getString("protocol")));
    }
}
