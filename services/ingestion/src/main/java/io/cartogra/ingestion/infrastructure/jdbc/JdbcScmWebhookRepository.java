package io.cartogra.ingestion.infrastructure.jdbc;

import io.cartogra.ingestion.repository.ScmWebhookRepository;
import io.cartogra.ingestion.domain.ScmWebhook;
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
public class JdbcScmWebhookRepository implements ScmWebhookRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcScmWebhookRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ScmWebhook> findByConnectionId(UUID connectionId) {
        String sql = """
                SELECT * FROM scm_webhooks
                WHERE scm_connection_id = :connectionId AND deleted_at IS NULL
                ORDER BY created_at DESC LIMIT 1
                """;
        var params = new MapSqlParameterSource().addValue("connectionId", connectionId);
        return jdbc.query(sql, params, WEBHOOK_MAPPER).stream().findFirst();
    }

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }

    private static List<String> toStringList(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        String[] values = (String[]) array.getArray();
        return List.of(values);
    }

    private static final RowMapper<ScmWebhook> WEBHOOK_MAPPER = (rs, _) -> new ScmWebhook(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("scm_connection_id", UUID.class),
            rs.getString("provider"),
            rs.getString("external_id"),
            rs.getString("target_url"),
            rs.getString("webhook_secret"),
            toStringList(rs.getArray("events")),
            rs.getString("status"),
            toInstant(rs.getTimestamp("last_received_at")),
            rs.getString("error_message"),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("updated_at")),
            toInstant(rs.getTimestamp("deleted_at"))
    );
}
