package io.cartogra.registry.infrastructure.jdbc;

import io.cartogra.registry.application.repository.ScmConnectionRepository;
import io.cartogra.registry.domain.ScmConnection;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcScmConnectionRepository implements ScmConnectionRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcScmConnectionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ScmConnection> findById(UUID tenantId, UUID id) {
        String sql = """
                SELECT * FROM scm_connections
                WHERE tenant_id = :tenantId AND id = :id AND deleted_at IS NULL
                """;
        var params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("id", id);
        return jdbc.query(sql, params, SCM_MAPPER).stream().findFirst();
    }

    @Override
    public List<ScmConnection> findAll(UUID tenantId, int limit, int offset) {
        String sql = """
                SELECT * FROM scm_connections
                WHERE tenant_id = :tenantId AND deleted_at IS NULL
                ORDER BY provider ASC, created_at DESC LIMIT :limit OFFSET :offset
                """;
        var params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("limit", limit)
                .addValue("offset", offset);
        return jdbc.query(sql, params, SCM_MAPPER);
    }

    @Override
    public long count(UUID tenantId) {
        String sql = "SELECT COUNT(*) FROM scm_connections WHERE tenant_id = :tenantId AND deleted_at IS NULL";
        Long result = jdbc.queryForObject(sql, new MapSqlParameterSource("tenantId", tenantId), Long.class);
        return result != null ? result : 0L;
    }

    @Override
    public ScmConnection save(ScmConnection connection) {
        String sql = """
                INSERT INTO scm_connections (id, tenant_id, provider, config, created_at, updated_at, deleted_at)
                VALUES (:id, :tenantId, :provider, CAST(:config AS JSONB), :createdAt, :updatedAt, :deletedAt)
                ON CONFLICT (id) DO UPDATE SET
                    provider   = EXCLUDED.provider,
                    config     = EXCLUDED.config,
                    updated_at = EXCLUDED.updated_at,
                    deleted_at = EXCLUDED.deleted_at
                RETURNING *
                """;
        return jdbc.queryForObject(sql, toParams(connection), SCM_MAPPER);
    }

    @Override
    public void softDelete(UUID tenantId, UUID id) {
        String sql = """
                UPDATE scm_connections SET deleted_at = now(), updated_at = now()
                WHERE tenant_id = :tenantId AND id = :id AND deleted_at IS NULL
                """;
        jdbc.update(sql, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("id", id));
    }

    private MapSqlParameterSource toParams(ScmConnection c) {
        return new MapSqlParameterSource()
                .addValue("id", c.id())
                .addValue("tenantId", c.tenantId())
                .addValue("provider", c.provider())
                .addValue("config", c.config())
                .addValue("createdAt", java.sql.Timestamp.from(c.createdAt()))
                .addValue("updatedAt", java.sql.Timestamp.from(c.updatedAt()))
                .addValue("deletedAt", c.deletedAt() != null ? java.sql.Timestamp.from(c.deletedAt()) : null);
    }

    private static final RowMapper<ScmConnection> SCM_MAPPER = (rs, _) -> new ScmConnection(
            UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("tenant_id")),
            rs.getString("provider"),
            rs.getString("config"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant(),
            rs.getTimestamp("deleted_at") != null ? rs.getTimestamp("deleted_at").toInstant() : null
    );
}
