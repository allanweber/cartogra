package io.cartogra.topology.infrastructure.jdbc;

import io.cartogra.topology.domain.UnresolvedResolution;
import io.cartogra.topology.domain.UnresolvedSpanService;
import io.cartogra.topology.repository.UnresolvedSpanServiceRepository;
import org.jspecify.annotations.Nullable;
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
public class JdbcUnresolvedSpanServiceRepository implements UnresolvedSpanServiceRepository {

    private static final RowMapper<UnresolvedSpanService> MAPPER = (rs, _) -> new UnresolvedSpanService(
            UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("tenant_id")),
            rs.getString("name"),
            rs.getTimestamp("first_seen_at").toInstant(),
            rs.getTimestamp("last_seen_at").toInstant(),
            rs.getLong("seen_count"),
            rs.getTimestamp("deleted_at") != null ? rs.getTimestamp("deleted_at").toInstant() : null,
            rs.getString("resolution") != null ? UnresolvedResolution.fromDbValue(rs.getString("resolution")) : null);

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
                  AND NOT EXISTS (SELECT 1 FROM unresolved_span_services
                                  WHERE tenant_id = :tenantId AND lower(name) = lower(:name)
                                    AND resolution = 'dismissed')
                ON CONFLICT (tenant_id, lower(name)) WHERE deleted_at IS NULL
                DO UPDATE SET seen_count = unresolved_span_services.seen_count + 1,
                              last_seen_at = EXCLUDED.last_seen_at,
                              updated_at = now()
                """;
        jdbc.update(insert, params);
    }

    @Override
    public List<UnresolvedSpanService> findActive(UUID tenantId, int limit, int offset) {
        String sql = """
                SELECT * FROM unresolved_span_services
                WHERE tenant_id = :tenantId AND deleted_at IS NULL
                ORDER BY seen_count DESC, last_seen_at DESC, id
                LIMIT :limit OFFSET :offset
                """;
        var params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("limit", limit)
                .addValue("offset", offset);
        return jdbc.query(sql, params, MAPPER);
    }

    @Override
    public long countActive(UUID tenantId) {
        String sql = """
                SELECT count(*) FROM unresolved_span_services
                WHERE tenant_id = :tenantId AND deleted_at IS NULL
                """;
        Long result = jdbc.queryForObject(sql, new MapSqlParameterSource().addValue("tenantId", tenantId), Long.class);
        return result != null ? result : 0L;
    }

    @Override
    public Optional<UnresolvedSpanService> findById(UUID tenantId, UUID id) {
        String sql = "SELECT * FROM unresolved_span_services WHERE tenant_id = :tenantId AND id = :id";
        var params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("id", id);
        return jdbc.query(sql, params, MAPPER).stream().findFirst();
    }

    @Override
    public boolean dismiss(UUID tenantId, UUID id, @Nullable UUID dismissedBy, Instant dismissedAt) {
        String sql = """
                UPDATE unresolved_span_services
                SET deleted_at = :dismissedAt, resolution = 'dismissed', resolved_by = :dismissedBy, updated_at = now()
                WHERE tenant_id = :tenantId AND id = :id AND deleted_at IS NULL
                """;
        return jdbc.update(sql, new MapSqlParameterSource()
                .addValue("dismissedAt", Timestamp.from(dismissedAt))
                .addValue("dismissedBy", dismissedBy)
                .addValue("tenantId", tenantId)
                .addValue("id", id)) > 0;
    }

    @Override
    public void markRegistered(UUID tenantId, String name, Instant registeredAt) {
        String sql = """
                UPDATE unresolved_span_services
                SET deleted_at = :registeredAt, resolution = 'registered', updated_at = now()
                WHERE tenant_id = :tenantId AND lower(name) = lower(:name) AND deleted_at IS NULL
                """;
        jdbc.update(sql, new MapSqlParameterSource()
                .addValue("registeredAt", Timestamp.from(registeredAt))
                .addValue("tenantId", tenantId)
                .addValue("name", name));
    }
}
