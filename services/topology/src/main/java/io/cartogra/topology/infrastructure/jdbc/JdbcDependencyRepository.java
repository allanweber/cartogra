package io.cartogra.topology.infrastructure.jdbc;

import io.cartogra.topology.domain.Dependency;
import io.cartogra.topology.domain.DependencyProtocol;
import io.cartogra.topology.domain.DependencyType;
import io.cartogra.topology.repository.DependencyFilter;
import io.cartogra.topology.repository.DependencyRepository;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcDependencyRepository implements DependencyRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcDependencyRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Dependency> findById(UUID tenantId, UUID id) {
        String sql = """
                SELECT * FROM dependencies
                WHERE tenant_id = :tenantId AND id = :id AND deleted_at IS NULL
                """;
        var params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("id", id);
        return jdbc.query(sql, params, DEPENDENCY_MAPPER).stream().findFirst();
    }

    @Override
    public List<Dependency> findAll(UUID tenantId, DependencyFilter filter, int limit, int offset) {
        var sql = new StringBuilder("""
                SELECT * FROM dependencies
                WHERE tenant_id = :tenantId AND deleted_at IS NULL
                """);
        var params = new MapSqlParameterSource().addValue("tenantId", tenantId);
        applyFilter(sql, params, filter);
        sql.append(" ORDER BY created_at ASC LIMIT :limit OFFSET :offset");
        params.addValue("limit", limit).addValue("offset", offset);
        return jdbc.query(sql.toString(), params, DEPENDENCY_MAPPER);
    }

    @Override
    public long count(UUID tenantId, DependencyFilter filter) {
        var sql = new StringBuilder("""
                SELECT COUNT(*) FROM dependencies
                WHERE tenant_id = :tenantId AND deleted_at IS NULL
                """);
        var params = new MapSqlParameterSource().addValue("tenantId", tenantId);
        applyFilter(sql, params, filter);
        Long result = jdbc.queryForObject(sql.toString(), params, Long.class);
        return result != null ? result : 0L;
    }

    @Override
    public List<Dependency> findByService(UUID tenantId, UUID serviceId) {
        String sql = """
                SELECT * FROM dependencies
                WHERE tenant_id = :tenantId AND deleted_at IS NULL
                  AND (source_service_id = :serviceId OR target_service_id = :serviceId)
                """;
        var params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("serviceId", serviceId);
        return jdbc.query(sql, params, DEPENDENCY_MAPPER);
    }

    @Override
    public Dependency save(Dependency dependency) {
        String sql = """
                INSERT INTO dependencies (
                    id, tenant_id, source_service_id, target_service_id,
                    dependency_type, protocol, metadata, created_at, updated_at, deleted_at
                ) VALUES (
                    :id, :tenantId, :sourceServiceId, :targetServiceId,
                    :type, :protocol, CAST(:metadata AS JSONB), :createdAt, :updatedAt, :deletedAt
                )
                ON CONFLICT (id) DO UPDATE SET
                    dependency_type = EXCLUDED.dependency_type,
                    protocol        = EXCLUDED.protocol,
                    metadata        = EXCLUDED.metadata,
                    updated_at      = EXCLUDED.updated_at,
                    deleted_at      = EXCLUDED.deleted_at
                RETURNING *
                """;
        return jdbc.queryForObject(sql, toParams(dependency), DEPENDENCY_MAPPER);
    }

    @Override
    public void softDelete(UUID tenantId, UUID id) {
        String sql = """
                UPDATE dependencies SET deleted_at = now(), updated_at = now()
                WHERE tenant_id = :tenantId AND id = :id AND deleted_at IS NULL
                """;
        jdbc.update(sql, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("id", id));
    }

    @Override
    public void softDeleteAllForService(UUID tenantId, UUID serviceId) {
        String sql = """
                UPDATE dependencies SET deleted_at = now(), updated_at = now()
                WHERE tenant_id = :tenantId AND deleted_at IS NULL
                  AND (source_service_id = :serviceId OR target_service_id = :serviceId)
                """;
        jdbc.update(sql, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("serviceId", serviceId));
    }

    @Override
    public Optional<Dependency> findByEdgeIdentity(UUID tenantId, UUID sourceServiceId, UUID targetServiceId,
            DependencyType type, DependencyProtocol protocol) {
        String sql = """
                SELECT * FROM dependencies
                WHERE tenant_id = :tenantId AND source_service_id = :sourceServiceId
                  AND target_service_id = :targetServiceId AND dependency_type = :type
                  AND protocol = :protocol AND deleted_at IS NULL
                """;
        var params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("sourceServiceId", sourceServiceId)
                .addValue("targetServiceId", targetServiceId)
                .addValue("type", type.toDbValue())
                .addValue("protocol", protocol.toDbValue());
        return jdbc.query(sql, params, DEPENDENCY_MAPPER).stream().findFirst();
    }

    private void applyFilter(StringBuilder sql, MapSqlParameterSource params, DependencyFilter filter) {
        if (filter.serviceId() != null) {
            sql.append(" AND (source_service_id = :serviceId OR target_service_id = :serviceId)");
            params.addValue("serviceId", filter.serviceId());
        }
        if (filter.type() != null) {
            sql.append(" AND dependency_type = :type");
            params.addValue("type", filter.type().toDbValue());
        }
        if (filter.protocol() != null) {
            sql.append(" AND protocol = :protocol");
            params.addValue("protocol", filter.protocol().toDbValue());
        }
    }

    private MapSqlParameterSource toParams(Dependency d) {
        return new MapSqlParameterSource()
                .addValue("id", d.id())
                .addValue("tenantId", d.tenantId())
                .addValue("sourceServiceId", d.sourceServiceId())
                .addValue("targetServiceId", d.targetServiceId())
                .addValue("type", d.type().toDbValue())
                .addValue("protocol", d.protocol().toDbValue())
                .addValue("metadata", d.metadata())
                .addValue("createdAt", Timestamp.from(d.createdAt()))
                .addValue("updatedAt", Timestamp.from(d.updatedAt()))
                .addValue("deletedAt", d.deletedAt() != null ? Timestamp.from(d.deletedAt()) : null);
    }

    private static final RowMapper<Dependency> DEPENDENCY_MAPPER = (rs, _) -> mapDependency(rs);

    private static Dependency mapDependency(ResultSet rs) throws SQLException {
        return new Dependency(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("tenant_id")),
                UUID.fromString(rs.getString("source_service_id")),
                UUID.fromString(rs.getString("target_service_id")),
                DependencyType.valueOf(rs.getString("dependency_type").toUpperCase(java.util.Locale.ROOT)),
                DependencyProtocol.valueOf(rs.getString("protocol").toUpperCase(java.util.Locale.ROOT)),
                rs.getString("metadata"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                rs.getTimestamp("deleted_at") != null ? rs.getTimestamp("deleted_at").toInstant() : null);
    }
}
