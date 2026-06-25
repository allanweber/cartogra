package io.cartogra.gateway.infrastructure.jdbc;

import io.cartogra.gateway.repository.TenantOidcConfigRepository;

import io.cartogra.gateway.domain.TenantOidcConfig;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcTenantOidcConfigRepository implements TenantOidcConfigRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcTenantOidcConfigRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<TenantOidcConfig> findByTenantId(UUID tenantId) {
        String sql = """
            SELECT * FROM tenant_oidc_configs
            WHERE tenant_id = :tenantId AND deleted_at IS NULL
            """;
        var params = new MapSqlParameterSource("tenantId", tenantId);
        return jdbc.query(sql, params, JdbcTenantOidcConfigRepository::mapRow).stream().findFirst();
    }

    @Override
    public Optional<TenantOidcConfig> findByIdAndTenantId(UUID id, UUID tenantId) {
        String sql = """
            SELECT * FROM tenant_oidc_configs
            WHERE id = :id AND tenant_id = :tenantId AND deleted_at IS NULL
            """;
        var params = new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("tenantId", tenantId);
        return jdbc.query(sql, params, JdbcTenantOidcConfigRepository::mapRow).stream().findFirst();
    }

    @Override
    public TenantOidcConfig save(TenantOidcConfig config) {
        if (config.id() == null) {
            return insert(config);
        }
        return update(config);
    }

    @Override
    public void softDeleteByTenantId(UUID tenantId) {
        String sql = "UPDATE tenant_oidc_configs SET deleted_at = now() WHERE tenant_id = :tenantId AND deleted_at IS NULL";
        jdbc.update(sql, new MapSqlParameterSource("tenantId", tenantId));
    }

    @Override
    public void softDeleteByIdAndTenantId(UUID id, UUID tenantId) {
        String sql = "UPDATE tenant_oidc_configs SET deleted_at = now() WHERE id = :id AND tenant_id = :tenantId AND deleted_at IS NULL";
        jdbc.update(sql, new MapSqlParameterSource().addValue("id", id).addValue("tenantId", tenantId));
    }

    private TenantOidcConfig insert(TenantOidcConfig config) {
        UUID id = UUID.randomUUID();
        String sql = """
            INSERT INTO tenant_oidc_configs (id, tenant_id, discovery_uri, client_id, client_secret, enabled, created_at, updated_at)
            VALUES (:id, :tenantId, :discoveryUri, :clientId, :clientSecret, :enabled, :now, :now)
            """;
        Instant now = Instant.now();
        var params = new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("tenantId", config.tenantId())
            .addValue("discoveryUri", config.discoveryUri())
            .addValue("clientId", config.clientId())
            .addValue("clientSecret", config.clientSecret())
            .addValue("enabled", config.enabled())
            .addValue("now", Timestamp.from(now));
        jdbc.update(sql, params);
        return new TenantOidcConfig(id, config.tenantId(), config.discoveryUri(),
            config.clientId(), config.clientSecret(), config.enabled(), now, now, null);
    }

    private TenantOidcConfig update(TenantOidcConfig config) {
        String sql = """
            UPDATE tenant_oidc_configs
            SET discovery_uri = :discoveryUri, client_id = :clientId,
                client_secret = :clientSecret, enabled = :enabled, updated_at = now()
            WHERE id = :id AND deleted_at IS NULL
            """;
        var params = new MapSqlParameterSource()
            .addValue("id", config.id())
            .addValue("discoveryUri", config.discoveryUri())
            .addValue("clientId", config.clientId())
            .addValue("clientSecret", config.clientSecret())
            .addValue("enabled", config.enabled());
        jdbc.update(sql, params);
        return config;
    }

    private static TenantOidcConfig mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new TenantOidcConfig(
            UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("tenant_id")),
            rs.getString("discovery_uri"),
            rs.getString("client_id"),
            rs.getString("client_secret"),
            rs.getBoolean("enabled"),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("updated_at")),
            toInstant(rs.getTimestamp("deleted_at"))
        );
    }

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }
}
