package io.cartogra.topology.infrastructure.jdbc;

import io.cartogra.topology.domain.DependencyDrift;
import io.cartogra.topology.domain.DriftType;
import io.cartogra.topology.repository.DependencyDriftRepository;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcDependencyDriftRepository implements DependencyDriftRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcDependencyDriftRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<DependencyDrift> findById(UUID tenantId, UUID id) {
        String sql = """
                SELECT * FROM dependency_drifts
                WHERE tenant_id = :tenantId AND id = :id
                """;
        var params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("id", id);
        return jdbc.query(sql, params, DRIFT_MAPPER).stream().findFirst();
    }

    @Override
    public List<DependencyDrift> findActive(UUID tenantId, int limit, int offset) {
        String sql = """
                SELECT * FROM dependency_drifts
                WHERE tenant_id = :tenantId AND resolved_at IS NULL
                ORDER BY detected_at DESC LIMIT :limit OFFSET :offset
                """;
        var params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("limit", limit)
                .addValue("offset", offset);
        return jdbc.query(sql, params, DRIFT_MAPPER);
    }

    @Override
    public long countActive(UUID tenantId) {
        String sql = """
                SELECT COUNT(*) FROM dependency_drifts
                WHERE tenant_id = :tenantId AND resolved_at IS NULL
                """;
        Long result = jdbc.queryForObject(sql, new MapSqlParameterSource().addValue("tenantId", tenantId), Long.class);
        return result != null ? result : 0L;
    }

    @Override
    public Optional<DependencyDrift> findActiveByEdge(UUID tenantId, UUID sourceServiceId, UUID targetServiceId,
            DriftType type) {
        String sql = """
                SELECT * FROM dependency_drifts
                WHERE tenant_id = :tenantId AND source_service_id = :sourceServiceId
                  AND target_service_id = :targetServiceId AND drift_type = :type
                  AND resolved_at IS NULL
                """;
        var params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("sourceServiceId", sourceServiceId)
                .addValue("targetServiceId", targetServiceId)
                .addValue("type", type.toDbValue());
        return jdbc.query(sql, params, DRIFT_MAPPER).stream().findFirst();
    }

    @Override
    public DependencyDrift save(DependencyDrift drift) {
        String sql = """
                INSERT INTO dependency_drifts (
                    id, tenant_id, source_service_id, target_service_id,
                    drift_type, detected_at, resolved_at, resolved_by
                ) VALUES (
                    :id, :tenantId, :sourceServiceId, :targetServiceId,
                    :type, :detectedAt, :resolvedAt, :resolvedBy
                )
                ON CONFLICT (id) DO UPDATE SET
                    resolved_at = EXCLUDED.resolved_at,
                    resolved_by = EXCLUDED.resolved_by
                RETURNING *
                """;
        var params = new MapSqlParameterSource()
                .addValue("id", drift.id())
                .addValue("tenantId", drift.tenantId())
                .addValue("sourceServiceId", drift.sourceServiceId())
                .addValue("targetServiceId", drift.targetServiceId())
                .addValue("type", drift.type().toDbValue())
                .addValue("detectedAt", Timestamp.from(drift.detectedAt()))
                .addValue("resolvedAt", drift.resolvedAt() != null ? Timestamp.from(drift.resolvedAt()) : null)
                .addValue("resolvedBy", drift.resolvedBy());
        return jdbc.queryForObject(sql, params, DRIFT_MAPPER);
    }

    @Override
    public void resolve(UUID tenantId, UUID id, UUID resolvedBy, Instant resolvedAt) {
        String sql = """
                UPDATE dependency_drifts
                SET resolved_at = :resolvedAt, resolved_by = :resolvedBy
                WHERE tenant_id = :tenantId AND id = :id AND resolved_at IS NULL
                """;
        jdbc.update(sql, new MapSqlParameterSource()
                .addValue("resolvedAt", Timestamp.from(resolvedAt))
                .addValue("resolvedBy", resolvedBy)
                .addValue("tenantId", tenantId)
                .addValue("id", id));
    }

    private static final RowMapper<DependencyDrift> DRIFT_MAPPER = (rs, _) -> mapDrift(rs);

    private static DependencyDrift mapDrift(ResultSet rs) throws SQLException {
        return new DependencyDrift(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("tenant_id")),
                UUID.fromString(rs.getString("source_service_id")),
                UUID.fromString(rs.getString("target_service_id")),
                DriftType.valueOf(rs.getString("drift_type").toUpperCase(java.util.Locale.ROOT)),
                rs.getTimestamp("detected_at").toInstant(),
                rs.getTimestamp("resolved_at") != null ? rs.getTimestamp("resolved_at").toInstant() : null,
                rs.getString("resolved_by") != null ? UUID.fromString(rs.getString("resolved_by")) : null);
    }
}
