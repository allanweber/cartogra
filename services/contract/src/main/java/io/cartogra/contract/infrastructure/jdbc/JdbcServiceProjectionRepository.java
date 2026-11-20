package io.cartogra.contract.infrastructure.jdbc;

import io.cartogra.contract.repository.ServiceProjectionRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcServiceProjectionRepository implements ServiceProjectionRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcServiceProjectionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void upsertNames(UUID tenantId, Map<UUID, String> names, Instant at) {
        Timestamp ts = Timestamp.from(at);
        jdbc.batchUpdate("""
                INSERT INTO services (tenant_id, service_id, name, updated_at)
                VALUES (:tenantId, :serviceId, :name, :at)
                ON CONFLICT (tenant_id, service_id) DO UPDATE SET name = EXCLUDED.name, updated_at = EXCLUDED.updated_at
                WHERE services.deleted_at IS NULL
                """, names.entrySet().stream()
                .map(e -> params(tenantId, e.getKey()).addValue("name", e.getValue()).addValue("at", ts))
                .toArray(MapSqlParameterSource[]::new));
    }

    @Override
    public boolean tombstone(UUID tenantId, UUID serviceId, Instant at) {
        return jdbc.update("""
                INSERT INTO services (tenant_id, service_id, updated_at, deleted_at)
                VALUES (:tenantId, :serviceId, :at, :at)
                ON CONFLICT (tenant_id, service_id) DO UPDATE SET deleted_at = EXCLUDED.deleted_at,
                    updated_at = EXCLUDED.updated_at
                WHERE services.deleted_at IS NULL
                """, params(tenantId, serviceId).addValue("at", Timestamp.from(at))) > 0;
    }

    @Override
    public boolean isDeleted(UUID tenantId, UUID serviceId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM services
                               WHERE tenant_id = :tenantId AND service_id = :serviceId AND deleted_at IS NOT NULL)
                """, params(tenantId, serviceId), Boolean.class));
    }

    @Override
    public Optional<String> findName(UUID tenantId, UUID serviceId) {
        return jdbc.queryForList("""
                SELECT name FROM services WHERE tenant_id = :tenantId AND service_id = :serviceId AND name IS NOT NULL
                """, params(tenantId, serviceId), String.class).stream().findFirst();
    }

    @Override
    public List<UUID> findUnnamed(UUID tenantId, int limit) {
        return jdbc.queryForList("""
                SELECT DISTINCT c.service_id FROM api_contracts c
                LEFT JOIN services s ON s.tenant_id = c.tenant_id AND s.service_id = c.service_id
                WHERE c.tenant_id = :tenantId AND c.deleted_at IS NULL AND s.name IS NULL AND s.deleted_at IS NULL
                LIMIT :limit
                """, new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("limit", limit), UUID.class);
    }

    @Override
    public void upsertExternalId(UUID tenantId, UUID serviceId, String externalId, Instant at) {
        MapSqlParameterSource params = params(tenantId, serviceId)
                .addValue("externalId", externalId)
                .addValue("at", Timestamp.from(at));
        jdbc.update("""
                UPDATE services SET external_id = NULL, updated_at = :at
                WHERE tenant_id = :tenantId AND external_id = :externalId AND service_id <> :serviceId
                  AND deleted_at IS NULL
                """, params);
        jdbc.update("""
                INSERT INTO services (tenant_id, service_id, external_id, updated_at)
                VALUES (:tenantId, :serviceId, :externalId, :at)
                ON CONFLICT (tenant_id, service_id) DO UPDATE SET external_id = EXCLUDED.external_id,
                    updated_at = EXCLUDED.updated_at
                WHERE services.deleted_at IS NULL
                """, params);
    }

    @Override
    public Optional<UUID> findLiveByExternalId(UUID tenantId, String externalId) {
        return jdbc.queryForList("""
                SELECT service_id FROM services
                WHERE tenant_id = :tenantId AND external_id = :externalId AND deleted_at IS NULL
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("externalId", externalId), UUID.class).stream().findFirst();
    }

    private static MapSqlParameterSource params(UUID tenantId, UUID serviceId) {
        return new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("serviceId", serviceId);
    }
}
