package io.cartogra.gateway.infrastructure.jdbc;

import io.cartogra.gateway.domain.ApiKey;
import io.cartogra.gateway.repository.ApiKeyRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public class JdbcApiKeyRepository implements ApiKeyRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcApiKeyRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ApiKey> findActiveByHash(String keyHash) {
        String sql = """
            SELECT id, tenant_id, scopes FROM api_keys
            WHERE key_hash = :hash
              AND revoked_at IS NULL
              AND deleted_at IS NULL
              AND (expires_at IS NULL OR expires_at > now())
            """;
        return jdbc.query(sql, new MapSqlParameterSource("hash", keyHash), JdbcApiKeyRepository::mapRow)
                .stream().findFirst();
    }

    @Override
    public boolean touchLastUsed(UUID id) {
        String sql = """
            UPDATE api_keys SET last_used_at = now()
            WHERE id = :id
              AND (last_used_at IS NULL OR last_used_at < now() - interval '1 minute')
            """;
        return jdbc.update(sql, new MapSqlParameterSource("id", id)) > 0;
    }

    private static ApiKey mapRow(ResultSet rs, int rowNum) throws SQLException {
        String[] scopes = (String[]) rs.getArray("scopes").getArray();
        return new ApiKey(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("tenant_id")),
                Set.of(scopes));
    }
}
