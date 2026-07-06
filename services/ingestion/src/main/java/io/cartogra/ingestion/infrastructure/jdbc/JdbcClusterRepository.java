package io.cartogra.ingestion.infrastructure.jdbc;

import io.cartogra.ingestion.domain.Cluster;
import io.cartogra.ingestion.domain.ClusterSource;
import io.cartogra.ingestion.domain.ClusterStatus;
import io.cartogra.ingestion.repository.ClusterRepository;
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
public class JdbcClusterRepository implements ClusterRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcClusterRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Cluster save(Cluster c) {
        String sql = """
                INSERT INTO k8s_clusters (
                    id, tenant_id, name, source, api_server_url, ca_cert_pem, sa_token,
                    skip_tls_verify, status, status_message, created_at, updated_at, deleted_at,
                    kubeconfig_encrypted)
                VALUES (
                    :id, :tenantId, :name, :source, :apiServerUrl, :caCertPem, :saToken,
                    :skipTlsVerify, :status, :statusMessage, :createdAt, :updatedAt, :deletedAt,
                    :kubeconfigEncrypted)
                ON CONFLICT (id) DO UPDATE SET
                    name                 = EXCLUDED.name,
                    api_server_url       = EXCLUDED.api_server_url,
                    ca_cert_pem          = EXCLUDED.ca_cert_pem,
                    sa_token             = EXCLUDED.sa_token,
                    skip_tls_verify      = EXCLUDED.skip_tls_verify,
                    status               = EXCLUDED.status,
                    status_message       = EXCLUDED.status_message,
                    updated_at           = EXCLUDED.updated_at,
                    deleted_at           = EXCLUDED.deleted_at,
                    kubeconfig_encrypted = EXCLUDED.kubeconfig_encrypted
                RETURNING *
                """;
        return jdbc.queryForObject(sql, toParams(c), CLUSTER_MAPPER);
    }

    @Override
    public Optional<Cluster> findById(UUID tenantId, UUID id) {
        String sql = """
                SELECT * FROM k8s_clusters
                WHERE tenant_id = :tenantId AND id = :id AND deleted_at IS NULL
                """;
        return jdbc.query(sql, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("id", id), CLUSTER_MAPPER).stream().findFirst();
    }

    @Override
    public List<Cluster> findAll(UUID tenantId, int limit, int offset) {
        String sql = """
                SELECT * FROM k8s_clusters
                WHERE tenant_id = :tenantId AND deleted_at IS NULL
                ORDER BY created_at DESC LIMIT :limit OFFSET :offset
                """;
        return jdbc.query(sql, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("limit", limit)
                .addValue("offset", offset), CLUSTER_MAPPER);
    }

    @Override
    public long count(UUID tenantId) {
        String sql = "SELECT COUNT(*) FROM k8s_clusters WHERE tenant_id = :tenantId AND deleted_at IS NULL";
        Long result = jdbc.queryForObject(sql, new MapSqlParameterSource("tenantId", tenantId), Long.class);
        return result != null ? result : 0L;
    }

    @Override
    public void softDelete(UUID tenantId, UUID id) {
        String sql = """
                UPDATE k8s_clusters SET deleted_at = now(), updated_at = now()
                WHERE tenant_id = :tenantId AND id = :id AND deleted_at IS NULL
                """;
        jdbc.update(sql, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("id", id));
    }

    @Override
    public void updateStatus(UUID id, ClusterStatus status, String statusMessage) {
        String sql = """
                UPDATE k8s_clusters SET status = :status, status_message = :statusMessage, updated_at = now()
                WHERE id = :id
                """;
        jdbc.update(sql, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("status", status.name())
                .addValue("statusMessage", statusMessage));
    }

    @Override
    public List<Cluster> findAllActive() {
        String sql = "SELECT * FROM k8s_clusters WHERE deleted_at IS NULL";
        return jdbc.query(sql, new MapSqlParameterSource(), CLUSTER_MAPPER);
    }

    private MapSqlParameterSource toParams(Cluster c) {
        return new MapSqlParameterSource()
                .addValue("id", c.id())
                .addValue("tenantId", c.tenantId())
                .addValue("name", c.name())
                .addValue("source", c.source().name())
                .addValue("apiServerUrl", c.apiServerUrl())
                .addValue("caCertPem", c.caCertPem())
                .addValue("saToken", c.saToken())
                .addValue("skipTlsVerify", c.skipTlsVerify())
                .addValue("status", c.status().name())
                .addValue("statusMessage", c.statusMessage())
                .addValue("createdAt", Timestamp.from(c.createdAt()))
                .addValue("updatedAt", Timestamp.from(c.updatedAt()))
                .addValue("deletedAt", c.deletedAt() != null ? Timestamp.from(c.deletedAt()) : null)
                .addValue("kubeconfigEncrypted", c.kubeconfigEncrypted());
    }

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }

    private static final RowMapper<Cluster> CLUSTER_MAPPER = (rs, _) -> new Cluster(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getString("name"),
            ClusterSource.valueOf(rs.getString("source")),
            rs.getString("api_server_url"),
            rs.getString("ca_cert_pem"),
            rs.getString("sa_token"),
            rs.getBoolean("skip_tls_verify"),
            ClusterStatus.valueOf(rs.getString("status")),
            rs.getString("status_message"),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("updated_at")),
            toInstant(rs.getTimestamp("deleted_at")),
            rs.getString("kubeconfig_encrypted")
    );
}
