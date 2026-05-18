package io.cartogra.gateway.infrastructure.jdbc;

import io.cartogra.gateway.domain.RefreshToken;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcRefreshTokenRepository implements RefreshTokenRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcRefreshTokenRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<RefreshToken> findActiveByHash(String tokenHash) {
        String sql = """
            SELECT * FROM refresh_tokens
            WHERE token_hash = :hash
              AND revoked_at IS NULL
              AND deleted_at IS NULL
              AND expires_at > now()
            """;
        var params = new MapSqlParameterSource("hash", tokenHash);
        return jdbc.query(sql, params, JdbcRefreshTokenRepository::mapRow).stream().findFirst();
    }

    @Override
    public RefreshToken save(RefreshToken token) {
        UUID id = UUID.randomUUID();
        String sql = """
            INSERT INTO refresh_tokens (id, tenant_id, user_id, token_hash, issued_at, expires_at, created_at)
            VALUES (:id, :tenantId, :userId, :tokenHash, :issuedAt, :expiresAt, :createdAt)
            """;
        Instant now = Instant.now();
        var params = new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("tenantId", token.tenantId())
            .addValue("userId", token.userId())
            .addValue("tokenHash", token.tokenHash())
            .addValue("issuedAt", Timestamp.from(now))
            .addValue("expiresAt", Timestamp.from(token.expiresAt()))
            .addValue("createdAt", Timestamp.from(now));
        jdbc.update(sql, params);
        return new RefreshToken(id, token.tenantId(), token.userId(), token.tokenHash(),
            now, token.expiresAt(), null, now, null);
    }

    @Override
    public void revoke(UUID id) {
        String sql = "UPDATE refresh_tokens SET revoked_at = now() WHERE id = :id";
        jdbc.update(sql, new MapSqlParameterSource("id", id));
    }

    @Override
    public void revokeAllForUser(UUID userId) {
        String sql = """
            UPDATE refresh_tokens SET revoked_at = now()
            WHERE user_id = :userId AND revoked_at IS NULL AND deleted_at IS NULL
            """;
        jdbc.update(sql, new MapSqlParameterSource("userId", userId));
    }

    @Override
    public int deleteExpiredAndRevoked(Duration retentionPeriod) {
        String sql = """
            DELETE FROM refresh_tokens
            WHERE expires_at < now() - :retention::interval
               OR (revoked_at IS NOT NULL AND revoked_at < now() - :retention::interval)
            """;
        var params = new MapSqlParameterSource("retention", retentionPeriod.toString());
        return jdbc.update(sql, params);
    }

    private static RefreshToken mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new RefreshToken(
            UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("tenant_id")),
            UUID.fromString(rs.getString("user_id")),
            rs.getString("token_hash"),
            toInstant(rs.getTimestamp("issued_at")),
            toInstant(rs.getTimestamp("expires_at")),
            toInstant(rs.getTimestamp("revoked_at")),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("deleted_at"))
        );
    }

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }
}
