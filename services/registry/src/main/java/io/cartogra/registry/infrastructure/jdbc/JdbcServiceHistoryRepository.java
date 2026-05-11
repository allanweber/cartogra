package io.cartogra.registry.infrastructure.jdbc;

import io.cartogra.registry.application.repository.ServiceHistoryRepository;
import io.cartogra.registry.domain.ServiceSnapshot;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcServiceHistoryRepository implements ServiceHistoryRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcServiceHistoryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(ServiceSnapshot snapshot) {
        String sql = """
                INSERT INTO services_history (id, service_id, tenant_id, snapshot, changed_by, changed_at)
                VALUES (:id, :serviceId, :tenantId, CAST(:snapshot AS JSONB), :changedBy, :changedAt)
                """;
        jdbc.update(sql, new MapSqlParameterSource()
                .addValue("id", snapshot.id())
                .addValue("serviceId", snapshot.serviceId())
                .addValue("tenantId", snapshot.tenantId())
                .addValue("snapshot", snapshot.snapshot())
                .addValue("changedBy", snapshot.changedBy())
                .addValue("changedAt", java.sql.Timestamp.from(snapshot.changedAt())));
    }

    @Override
    public List<ServiceSnapshot> findByServiceId(UUID tenantId, UUID serviceId, int limit, int offset) {
        String sql = """
                SELECT * FROM services_history
                WHERE tenant_id = :tenantId AND service_id = :serviceId
                ORDER BY changed_at DESC LIMIT :limit OFFSET :offset
                """;
        var params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("serviceId", serviceId)
                .addValue("limit", limit)
                .addValue("offset", offset);
        return jdbc.query(sql, params, SNAPSHOT_MAPPER);
    }

    @Override
    public Optional<ServiceSnapshot> findAtPointInTime(UUID tenantId, UUID serviceId, Instant at) {
        String sql = """
                SELECT * FROM services_history
                WHERE tenant_id = :tenantId AND service_id = :serviceId AND changed_at <= :at
                ORDER BY changed_at DESC LIMIT 1
                """;
        var params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("serviceId", serviceId)
                .addValue("at", java.sql.Timestamp.from(at));
        return jdbc.query(sql, params, SNAPSHOT_MAPPER).stream().findFirst();
    }

    private static final RowMapper<ServiceSnapshot> SNAPSHOT_MAPPER = (rs, _) -> new ServiceSnapshot(
            UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("service_id")),
            UUID.fromString(rs.getString("tenant_id")),
            rs.getString("snapshot"),
            rs.getString("changed_by") != null ? UUID.fromString(rs.getString("changed_by")) : null,
            rs.getTimestamp("changed_at").toInstant()
    );
}
