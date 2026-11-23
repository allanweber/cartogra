package io.cartogra.contract.infrastructure.jdbc;

import io.cartogra.contract.domain.Consumer;
import io.cartogra.contract.domain.InboundEdge;
import io.cartogra.contract.repository.InboundEdgeRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public class JdbcInboundEdgeRepository implements InboundEdgeRepository {

    private static final String LIVE_CONSUMER_EDGES = """
            FROM inbound_edges e
            LEFT JOIN services s ON s.tenant_id = e.tenant_id AND s.service_id = e.source_service_id
            WHERE e.tenant_id = :tenantId AND e.target_service_id = :targetId AND e.removed_at IS NULL
              AND e.source_service_id <> e.target_service_id
              AND e.protocol IN (:protocols)
              AND s.deleted_at IS NULL
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcInboundEdgeRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void upsert(UUID tenantId, InboundEdge edge, Instant at) {
        jdbc.update("""
                INSERT INTO inbound_edges (tenant_id, edge_id, source_service_id, target_service_id,
                                           dependency_type, protocol, updated_at)
                VALUES (:tenantId, :edgeId, :sourceId, :targetId, :type, :protocol, :at)
                ON CONFLICT (tenant_id, edge_id) DO UPDATE SET
                    source_service_id = EXCLUDED.source_service_id,
                    target_service_id = EXCLUDED.target_service_id,
                    dependency_type = EXCLUDED.dependency_type,
                    protocol = EXCLUDED.protocol,
                    updated_at = EXCLUDED.updated_at
                WHERE inbound_edges.removed_at IS NULL
                """, params(tenantId, edge, at));
    }

    @Override
    public void markRemoved(UUID tenantId, InboundEdge edge, Instant at) {
        jdbc.update("""
                INSERT INTO inbound_edges (tenant_id, edge_id, source_service_id, target_service_id,
                                           dependency_type, protocol, updated_at, removed_at)
                VALUES (:tenantId, :edgeId, :sourceId, :targetId, :type, :protocol, :at, :at)
                ON CONFLICT (tenant_id, edge_id) DO UPDATE SET removed_at = EXCLUDED.removed_at,
                    updated_at = EXCLUDED.updated_at
                WHERE inbound_edges.removed_at IS NULL
                """, params(tenantId, edge, at));
    }

    @Override
    public List<Consumer> findConsumers(UUID tenantId, UUID targetServiceId, Collection<String> protocols, int limit,
            int offset) {
        if (protocols.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                SELECT e.source_service_id, MAX(s.name) AS name,
                       array_agg(DISTINCT e.dependency_type ORDER BY e.dependency_type) AS types,
                       array_agg(DISTINCT e.protocol ORDER BY e.protocol) AS protocols
                """ + LIVE_CONSUMER_EDGES + """
                GROUP BY e.source_service_id
                ORDER BY MAX(s.name) NULLS LAST, e.source_service_id
                LIMIT :limit OFFSET :offset
                """, query(tenantId, targetServiceId, protocols).addValue("limit", limit).addValue("offset", offset),
                (rs, _) -> new Consumer(
                        rs.getObject("source_service_id", UUID.class),
                        rs.getString("name"),
                        strings(rs.getArray("types")),
                        strings(rs.getArray("protocols"))));
    }

    @Override
    public long countConsumers(UUID tenantId, UUID targetServiceId, Collection<String> protocols) {
        if (protocols.isEmpty()) {
            return 0;
        }
        Long count = jdbc.queryForObject("SELECT COUNT(DISTINCT e.source_service_id) " + LIVE_CONSUMER_EDGES,
                query(tenantId, targetServiceId, protocols), Long.class);
        return count == null ? 0 : count;
    }

    private static List<String> strings(Array array) throws SQLException {
        return Arrays.stream((Object[]) array.getArray()).map(String::valueOf).toList();
    }

    private static MapSqlParameterSource query(UUID tenantId, UUID targetServiceId, Collection<String> protocols) {
        return new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("targetId", targetServiceId)
                .addValue("protocols", protocols);
    }

    private static MapSqlParameterSource params(UUID tenantId, InboundEdge edge, Instant at) {
        return new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("edgeId", edge.edgeId())
                .addValue("sourceId", edge.sourceServiceId())
                .addValue("targetId", edge.targetServiceId())
                .addValue("type", edge.dependencyType())
                .addValue("protocol", edge.protocol())
                .addValue("at", Timestamp.from(at));
    }
}
