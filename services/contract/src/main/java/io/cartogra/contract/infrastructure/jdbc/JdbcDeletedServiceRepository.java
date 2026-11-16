package io.cartogra.contract.infrastructure.jdbc;

import io.cartogra.contract.repository.DeletedServiceRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@Repository
public class JdbcDeletedServiceRepository implements DeletedServiceRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcDeletedServiceRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean exists(UUID tenantId, UUID serviceId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM deleted_services WHERE tenant_id = :tenantId AND service_id = :serviceId)
                """, params(tenantId, serviceId), Boolean.class));
    }

    @Override
    public boolean insert(UUID tenantId, UUID serviceId, Instant deletedAt) {
        return jdbc.update("""
                INSERT INTO deleted_services (tenant_id, service_id, deleted_at)
                VALUES (:tenantId, :serviceId, :deletedAt)
                ON CONFLICT DO NOTHING
                """, params(tenantId, serviceId).addValue("deletedAt", Timestamp.from(deletedAt))) > 0;
    }

    private static MapSqlParameterSource params(UUID tenantId, UUID serviceId) {
        return new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("serviceId", serviceId);
    }
}
