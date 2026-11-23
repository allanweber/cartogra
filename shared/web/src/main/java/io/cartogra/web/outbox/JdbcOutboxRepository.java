package io.cartogra.web.outbox;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reads and writes the {@code outbox} table of the service schema. Each service owns the migration that creates
 * it: {@code id BIGSERIAL}, {@code tenant_id}, {@code topic}, {@code record_key}, {@code payload TEXT},
 * {@code headers JSONB}, {@code created_at}, {@code attempts}, {@code last_error}.
 */
public class JdbcOutboxRepository implements OutboxRepository {

    private static final TypeReference<Map<String, String>> HEADERS = new TypeReference<>() {
    };
    private static final int MAX_ERROR_LENGTH = 1000;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcOutboxRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public void insert(List<OutboxEntry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("""
                INSERT INTO outbox (tenant_id, topic, record_key, payload, headers)
                VALUES (:tenantId, :topic, :key, :payload, CAST(:headers AS jsonb))
                """, entries.stream().map(e -> new MapSqlParameterSource()
                .addValue("tenantId", e.tenantId())
                .addValue("topic", e.topic())
                .addValue("key", e.key())
                .addValue("payload", e.payload())
                .addValue("headers", objectMapper.writeValueAsString(e.headers())))
                .toArray(MapSqlParameterSource[]::new));
    }

    @Override
    public List<OutboxMessage> findBatch(int limit) {
        return jdbc.query("""
                SELECT id, tenant_id, topic, record_key, payload, headers::text AS headers
                FROM outbox
                ORDER BY id
                LIMIT :limit
                """, new MapSqlParameterSource("limit", limit), (rs, _) -> new OutboxMessage(
                rs.getLong("id"),
                rs.getObject("tenant_id", UUID.class),
                rs.getString("topic"),
                rs.getString("record_key"),
                rs.getString("payload"),
                objectMapper.readValue(rs.getString("headers"), HEADERS)));
    }

    @Override
    public void delete(long id) {
        jdbc.update("DELETE FROM outbox WHERE id = :id", new MapSqlParameterSource("id", id));
    }

    @Override
    public void recordFailure(long id, String error) {
        String trimmed = error.length() > MAX_ERROR_LENGTH ? error.substring(0, MAX_ERROR_LENGTH) : error;
        jdbc.update("UPDATE outbox SET attempts = attempts + 1, last_error = :error WHERE id = :id",
                new MapSqlParameterSource().addValue("id", id).addValue("error", trimmed));
    }
}
