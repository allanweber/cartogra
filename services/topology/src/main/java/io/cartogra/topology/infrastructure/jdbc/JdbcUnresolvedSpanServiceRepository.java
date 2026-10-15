package io.cartogra.topology.infrastructure.jdbc;

import io.cartogra.topology.repository.UnresolvedSpanServiceRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@Repository
public class JdbcUnresolvedSpanServiceRepository implements UnresolvedSpanServiceRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcUnresolvedSpanServiceRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(UUID tenantId, String name, Instant seenAt, int maxPerTenant) {
        var params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("name", name)
                .addValue("seenAt", Timestamp.from(seenAt))
                .addValue("max", maxPerTenant);
        String bump = """
                UPDATE unresolved_span_services
                SET seen_count = seen_count + 1, last_seen_at = :seenAt, updated_at = now()
                WHERE tenant_id = :tenantId AND lower(name) = lower(:name) AND deleted_at IS NULL
                """;
        if (jdbc.update(bump, params) > 0) {
            return;
        }
        String insert = """
                INSERT INTO unresolved_span_services (tenant_id, name, first_seen_at, last_seen_at)
                SELECT :tenantId, :name, :seenAt, :seenAt
                WHERE (SELECT count(*) FROM unresolved_span_services
                       WHERE tenant_id = :tenantId AND deleted_at IS NULL) < :max
                ON CONFLICT (tenant_id, lower(name)) WHERE deleted_at IS NULL
                DO UPDATE SET seen_count = unresolved_span_services.seen_count + 1,
                              last_seen_at = EXCLUDED.last_seen_at,
                              updated_at = now()
                """;
        jdbc.update(insert, params);
    }
}
