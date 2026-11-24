package io.cartogra.registry.infrastructure.jdbc;

import io.cartogra.registry.domain.ApiKey;
import io.cartogra.registry.repository.ApiKeyRepository;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcApiKeyRepository implements ApiKeyRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcApiKeyRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ApiKey insert(ApiKey k) {
        String sql = """
                INSERT INTO api_keys (id, tenant_id, name, key_hash, key_prefix, scopes, expires_at,
                                      created_by, created_at, updated_at)
                VALUES (:id, :tenantId, :name, :keyHash, :keyPrefix, string_to_array(:scopes, ','), :expiresAt,
                        :createdBy, :createdAt, :updatedAt)
                RETURNING *
                """;
        var params = new MapSqlParameterSource()
                .addValue("id", k.id())
                .addValue("tenantId", k.tenantId())
                .addValue("name", k.name())
                .addValue("keyHash", k.keyHash())
                .addValue("keyPrefix", k.keyPrefix())
                .addValue("scopes", String.join(",", k.scopes()))
                .addValue("expiresAt", toTimestamp(k.expiresAt()))
                .addValue("createdBy", k.createdBy())
                .addValue("createdAt", toTimestamp(k.createdAt()))
                .addValue("updatedAt", toTimestamp(k.updatedAt()));
        return jdbc.queryForObject(sql, params, MAPPER);
    }

    @Override
    public Optional<ApiKey> findById(UUID tenantId, UUID id) {
        String sql = "SELECT * FROM api_keys WHERE tenant_id = :tenantId AND id = :id AND deleted_at IS NULL";
        var params = new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("id", id);
        return jdbc.query(sql, params, MAPPER).stream().findFirst();
    }

    @Override
    public List<ApiKey> findAll(UUID tenantId, int limit, int offset) {
        String sql = """
                SELECT * FROM api_keys
                WHERE tenant_id = :tenantId AND deleted_at IS NULL
                ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset
                """;
        var params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("limit", limit)
                .addValue("offset", offset);
        return jdbc.query(sql, params, MAPPER);
    }

    @Override
    public long count(UUID tenantId) {
        String sql = "SELECT COUNT(*) FROM api_keys WHERE tenant_id = :tenantId AND deleted_at IS NULL";
        Long result = jdbc.queryForObject(sql, new MapSqlParameterSource("tenantId", tenantId), Long.class);
        return result != null ? result : 0L;
    }

    @Override
    public long countActive(UUID tenantId) {
        String sql = """
                SELECT COUNT(*) FROM api_keys
                WHERE tenant_id = :tenantId AND deleted_at IS NULL AND revoked_at IS NULL
                  AND (expires_at IS NULL OR expires_at > now())
                """;
        Long result = jdbc.queryForObject(sql, new MapSqlParameterSource("tenantId", tenantId), Long.class);
        return result != null ? result : 0L;
    }

    @Override
    public boolean revoke(UUID tenantId, UUID id) {
        String sql = """
                UPDATE api_keys SET revoked_at = now(), updated_at = now()
                WHERE tenant_id = :tenantId AND id = :id AND deleted_at IS NULL AND revoked_at IS NULL
                """;
        var params = new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("id", id);
        return jdbc.update(sql, params) > 0;
    }

    private static Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private static final RowMapper<ApiKey> MAPPER = (rs, _) -> {
        Array scopes = rs.getArray("scopes");
        try {
            return new ApiKey(
                    rs.getObject("id", UUID.class),
                    rs.getObject("tenant_id", UUID.class),
                    rs.getString("name"),
                    rs.getString("key_hash"),
                    rs.getString("key_prefix"),
                    List.of((String[]) scopes.getArray()),
                    toInstant(rs.getTimestamp("expires_at")),
                    toInstant(rs.getTimestamp("revoked_at")),
                    toInstant(rs.getTimestamp("last_used_at")),
                    rs.getObject("created_by", UUID.class),
                    toInstant(rs.getTimestamp("created_at")),
                    toInstant(rs.getTimestamp("updated_at")));
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read the scopes of an API key", e);
        } finally {
            scopes.free();
        }
    };
}
