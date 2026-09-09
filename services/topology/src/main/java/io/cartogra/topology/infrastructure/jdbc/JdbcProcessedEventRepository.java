package io.cartogra.topology.infrastructure.jdbc;

import io.cartogra.topology.repository.ProcessedEventRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public class JdbcProcessedEventRepository implements ProcessedEventRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcProcessedEventRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean markProcessed(UUID tenantId, UUID eventId) {
        String sql = """
                INSERT INTO processed_events (tenant_id, event_id)
                VALUES (:tenantId, :eventId)
                ON CONFLICT (tenant_id, event_id) DO NOTHING
                """;
        var params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("eventId", eventId);
        return jdbc.update(sql, params) == 1;
    }
}
