package io.cartogra.ingestion.infrastructure.jdbc;

import io.cartogra.ingestion.repository.SyncJobRepository;
import io.cartogra.ingestion.domain.SyncJob;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcSyncJobRepository implements SyncJobRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcSyncJobRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public SyncJob save(SyncJob job) {
        var params = new MapSqlParameterSource()
                .addValue("tenantId", job.tenantId())
                .addValue("connectionId", job.connectionId())
                .addValue("providerType", job.providerType())
                .addValue("status", job.status())
                .addValue("createdAt", Timestamp.from(job.createdAt()))
                .addValue("updatedAt", Timestamp.from(job.updatedAt()));

        var keyHolder = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO sync_jobs (tenant_id, connection_id, provider_type, status, created_at, updated_at)
                VALUES (:tenantId, :connectionId, :providerType, :status, :createdAt, :updatedAt)
                """, params, keyHolder, new String[]{"id"});

        UUID generatedId = (UUID) keyHolder.getKeys().get("id");
        return new SyncJob(
                generatedId, job.tenantId(), job.connectionId(), job.providerType(),
                job.status(), null, null, null, null,
                job.createdAt(), job.updatedAt(), null
        );
    }

    @Override
    public Optional<SyncJob> findById(UUID tenantId, UUID jobId) {
        var params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("jobId", jobId);
        var results = jdbc.query("""
                SELECT * FROM sync_jobs
                WHERE id = :jobId AND tenant_id = :tenantId AND deleted_at IS NULL
                """, params, (rs, _) -> mapRow(rs));
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    public void markRunning(UUID jobId) {
        var params = new MapSqlParameterSource()
                .addValue("jobId", jobId)
                .addValue("now", Timestamp.from(Instant.now()));
        jdbc.update("""
                UPDATE sync_jobs
                SET status = 'RUNNING', started_at = :now, updated_at = :now
                WHERE id = :jobId
                """, params);
    }

    @Override
    public void markCompleted(UUID jobId, int repositoriesSynced) {
        var params = new MapSqlParameterSource()
                .addValue("jobId", jobId)
                .addValue("repositoriesSynced", repositoriesSynced)
                .addValue("now", Timestamp.from(Instant.now()));
        jdbc.update("""
                UPDATE sync_jobs
                SET status = 'COMPLETED', completed_at = :now, repositories_synced = :repositoriesSynced, updated_at = :now
                WHERE id = :jobId
                """, params);
    }

    @Override
    public void markFailed(UUID jobId, String errorMessage) {
        var params = new MapSqlParameterSource()
                .addValue("jobId", jobId)
                .addValue("errorMessage", errorMessage)
                .addValue("now", Timestamp.from(Instant.now()));
        jdbc.update("""
                UPDATE sync_jobs
                SET status = 'FAILED', completed_at = :now, error_message = :errorMessage, updated_at = :now
                WHERE id = :jobId
                """, params);
    }

    @Override
    public boolean existsRunningForConnection(UUID tenantId, UUID connectionId) {
        var params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("connectionId", connectionId);
        var results = jdbc.query("""
                SELECT id FROM sync_jobs
                WHERE tenant_id = :tenantId AND connection_id = :connectionId
                  AND status = 'RUNNING' AND deleted_at IS NULL
                LIMIT 1
                """, params, (rs, _) -> rs.getObject("id", UUID.class));
        return !results.isEmpty();
    }

    @Override
    public Optional<SyncJob> findRunningForConnection(UUID tenantId, UUID connectionId) {
        var params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("connectionId", connectionId);
        var results = jdbc.query("""
                SELECT * FROM sync_jobs
                WHERE tenant_id = :tenantId AND connection_id = :connectionId
                  AND status = 'RUNNING' AND deleted_at IS NULL
                LIMIT 1
                """, params, (rs, _) -> mapRow(rs));
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    public List<SyncJob> findStaleRunning(Instant updatedBefore) {
        var params = new MapSqlParameterSource()
                .addValue("threshold", Timestamp.from(updatedBefore));
        return jdbc.query("""
                SELECT * FROM sync_jobs
                WHERE status = 'RUNNING' AND updated_at < :threshold AND deleted_at IS NULL
                """, params, (rs, _) -> mapRow(rs));
    }

    private SyncJob mapRow(ResultSet rs) throws SQLException {
        return new SyncJob(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("connection_id", UUID.class),
                rs.getString("provider_type"),
                rs.getString("status"),
                toInstant(rs.getTimestamp("started_at")),
                toInstant(rs.getTimestamp("completed_at")),
                rs.getString("error_message"),
                rs.getObject("repositories_synced") != null ? rs.getInt("repositories_synced") : null,
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")),
                toInstant(rs.getTimestamp("deleted_at"))
        );
    }

    private Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }
}
