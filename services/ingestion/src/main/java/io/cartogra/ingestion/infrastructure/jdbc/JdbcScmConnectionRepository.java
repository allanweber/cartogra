package io.cartogra.ingestion.infrastructure.jdbc;

import io.cartogra.ingestion.repository.ScmConnectionRepository;
import io.cartogra.ingestion.domain.ScmConnection;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
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
    public ScmConnection save(ScmConnection c) {
        String sql = """
                INSERT INTO scm_connections (
                    id, tenant_id, provider, config, sync_scheduler, poll_interval_minutes,
                    next_sync_at, last_sync_at, last_sync_status, last_sync_error, webhook_enabled,
                    created_at, updated_at, deleted_at)
                VALUES (
                    :id, :tenantId, :provider, CAST(:config AS JSONB), :syncScheduler, :pollIntervalMinutes,
                    :nextSyncAt, :lastSyncAt, :lastSyncStatus, :lastSyncError, :webhookEnabled,
                    :createdAt, :updatedAt, :deletedAt)
                ON CONFLICT (id) DO UPDATE SET
                    provider              = EXCLUDED.provider,
                    config                = EXCLUDED.config,
                    sync_scheduler        = EXCLUDED.sync_scheduler,
                    poll_interval_minutes = EXCLUDED.poll_interval_minutes,
                    next_sync_at          = EXCLUDED.next_sync_at,
                    last_sync_at          = EXCLUDED.last_sync_at,
                    last_sync_status      = EXCLUDED.last_sync_status,
                    last_sync_error       = EXCLUDED.last_sync_error,
                    webhook_enabled       = EXCLUDED.webhook_enabled,
                    updated_at            = EXCLUDED.updated_at,
                    deleted_at            = EXCLUDED.deleted_at
                RETURNING *
                """;
        return jdbc.queryForObject(sql, toParams(c), SCM_MAPPER);
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

    @Override
    public List<ScmConnection> findDue() {
        String sql = """
                SELECT * FROM scm_connections
                WHERE sync_scheduler = true AND deleted_at IS NULL
                  AND (next_sync_at IS NULL OR next_sync_at <= now())
                ORDER BY next_sync_at ASC NULLS FIRST
                """;
        return jdbc.query(sql, new MapSqlParameterSource(), SCM_MAPPER);
    }

    @Override
    public void updateSyncTimestamps(UUID id, Instant lastSyncAt, Instant nextSyncAt) {
        String sql = """
                UPDATE scm_connections
                SET last_sync_at = :lastSyncAt, next_sync_at = :nextSyncAt, updated_at = now()
                WHERE id = :id
                """;
        jdbc.update(sql, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("lastSyncAt", Timestamp.from(lastSyncAt))
                .addValue("nextSyncAt", Timestamp.from(nextSyncAt)));
    }

    @Override
    public Optional<ScmConnection> findByIdForWebhook(UUID id) {
        String sql = """
                SELECT * FROM scm_connections
                WHERE id = :id AND webhook_enabled = true AND deleted_at IS NULL
                """;
        return jdbc.query(sql, new MapSqlParameterSource("id", id), SCM_MAPPER).stream().findFirst();
    }

    @Override
    public void updateSyncResult(UUID id, String status, String error, Instant lastSyncAt) {
        String sql = """
                UPDATE scm_connections
                SET last_sync_status = :status, last_sync_error = :error, last_sync_at = :lastSyncAt, updated_at = now()
                WHERE id = :id
                """;
        jdbc.update(sql, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("status", status)
                .addValue("error", error)
                .addValue("lastSyncAt", Timestamp.from(lastSyncAt)));
    }

    private MapSqlParameterSource toParams(ScmConnection c) {
        return new MapSqlParameterSource()
                .addValue("id", c.id())
                .addValue("tenantId", c.tenantId())
                .addValue("provider", c.provider())
                .addValue("config", c.config())
                .addValue("syncScheduler", c.syncScheduler())
                .addValue("pollIntervalMinutes", c.pollIntervalMinutes())
                .addValue("nextSyncAt", toTimestamp(c.nextSyncAt()))
                .addValue("lastSyncAt", toTimestamp(c.lastSyncAt()))
                .addValue("lastSyncStatus", c.lastSyncStatus())
                .addValue("lastSyncError", c.lastSyncError())
                .addValue("webhookEnabled", c.webhookEnabled())
                .addValue("createdAt", Timestamp.from(c.createdAt()))
                .addValue("updatedAt", Timestamp.from(c.updatedAt()))
                .addValue("deletedAt", toTimestamp(c.deletedAt()));
    }

    private static Timestamp toTimestamp(Instant instant) {
        return instant != null ? Timestamp.from(instant) : null;
    }

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }

    private static final RowMapper<ScmConnection> SCM_MAPPER = (rs, _) -> new ScmConnection(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getString("provider"),
            rs.getString("config"),
            rs.getBoolean("sync_scheduler"),
            rs.getInt("poll_interval_minutes"),
            toInstant(rs.getTimestamp("next_sync_at")),
            toInstant(rs.getTimestamp("last_sync_at")),
            rs.getString("last_sync_status"),
            rs.getString("last_sync_error"),
            rs.getBoolean("webhook_enabled"),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("updated_at")),
            toInstant(rs.getTimestamp("deleted_at"))
    );
}
