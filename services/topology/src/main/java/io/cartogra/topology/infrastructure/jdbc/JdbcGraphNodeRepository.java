package io.cartogra.topology.infrastructure.jdbc;

import io.cartogra.topology.domain.GraphNode;
import io.cartogra.topology.repository.GraphNodeRepository;
import io.cartogra.topology.repository.GraphNodeUpsert;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcGraphNodeRepository implements GraphNodeRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcGraphNodeRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void upsert(GraphNodeUpsert command) {
        String sql = """
                INSERT INTO graph_nodes (tenant_id, service_id, name, team_id, tier, health_status)
                VALUES (:tenantId, :serviceId, :name, :teamId, :tier, :healthStatus)
                ON CONFLICT (tenant_id, service_id) DO UPDATE SET
                    name = EXCLUDED.name,
                    team_id = EXCLUDED.team_id,
                    tier = EXCLUDED.tier,
                    health_status = EXCLUDED.health_status,
                    deleted_at = NULL,
                    updated_at = now()
                """;
        var params = new MapSqlParameterSource()
                .addValue("tenantId", command.tenantId())
                .addValue("serviceId", command.serviceId())
                .addValue("name", command.name())
                .addValue("teamId", command.teamId())
                .addValue("tier", command.tier())
                .addValue("healthStatus", command.healthStatus());
        jdbc.update(sql, params);
    }

    @Override
    public void softDelete(UUID tenantId, UUID serviceId, Instant deletedAt) {
        String sql = """
                UPDATE graph_nodes
                SET deleted_at = :deletedAt, updated_at = now()
                WHERE tenant_id = :tenantId AND service_id = :serviceId
                """;
        var params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("serviceId", serviceId)
                .addValue("deletedAt", java.sql.Timestamp.from(deletedAt));
        jdbc.update(sql, params);
    }

    @Override
    public Optional<GraphNode> findByServiceId(UUID tenantId, UUID serviceId) {
        String sql = """
                SELECT * FROM graph_nodes
                WHERE tenant_id = :tenantId AND service_id = :serviceId
                """;
        var params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("serviceId", serviceId);
        return jdbc.query(sql, params, GRAPH_NODE_MAPPER).stream().findFirst();
    }

    @Override
    public List<GraphNode> findForGraph(UUID tenantId, @Nullable UUID teamId, int limit) {
        var sql = new StringBuilder("""
                SELECT * FROM graph_nodes
                WHERE tenant_id = :tenantId AND deleted_at IS NULL
                """);
        var params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("limit", limit);
        if (teamId != null) {
            sql.append(" AND team_id = :teamId");
            params.addValue("teamId", teamId);
        }
        sql.append(" ORDER BY name ASC, service_id ASC LIMIT :limit");
        return jdbc.query(sql.toString(), params, GRAPH_NODE_MAPPER);
    }

    @Override
    public List<GraphNode> findByServiceIds(UUID tenantId, Collection<UUID> serviceIds, int limit) {
        if (serviceIds.isEmpty()) {
            return List.of();
        }
        String sql = """
                SELECT * FROM graph_nodes
                WHERE tenant_id = :tenantId AND deleted_at IS NULL AND service_id IN (:serviceIds)
                ORDER BY name ASC, service_id ASC LIMIT :limit
                """;
        var params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("serviceIds", serviceIds)
                .addValue("limit", limit);
        return jdbc.query(sql, params, GRAPH_NODE_MAPPER);
    }

    private static final RowMapper<GraphNode> GRAPH_NODE_MAPPER = (rs, _) -> mapGraphNode(rs);

    private static GraphNode mapGraphNode(ResultSet rs) throws SQLException {
        return new GraphNode(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("tenant_id")),
                UUID.fromString(rs.getString("service_id")),
                rs.getString("name"),
                rs.getString("team_id") != null ? UUID.fromString(rs.getString("team_id")) : null,
                rs.getString("tier"),
                rs.getString("health_status"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                rs.getTimestamp("deleted_at") != null ? rs.getTimestamp("deleted_at").toInstant() : null);
    }
}
