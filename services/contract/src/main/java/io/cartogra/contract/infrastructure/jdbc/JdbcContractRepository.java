package io.cartogra.contract.infrastructure.jdbc;

import io.cartogra.contract.domain.ApiContract;
import io.cartogra.contract.repository.ContractRepository;
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
public class JdbcContractRepository implements ContractRepository {

    private static final RowMapper<ApiContract> MAPPER = (rs, _) -> {
        Timestamp deletedAt = rs.getTimestamp("deleted_at");
        return new ApiContract(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("service_id", UUID.class),
                rs.getString("spec_type"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                deletedAt == null ? null : deletedAt.toInstant());
    };

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcContractRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(ApiContract contract) {
        jdbc.update("""
                INSERT INTO api_contracts (id, tenant_id, service_id, spec_type, created_at, updated_at)
                VALUES (:id, :tenantId, :serviceId, :specType, :createdAt, :updatedAt)
                """, new MapSqlParameterSource()
                .addValue("id", contract.id())
                .addValue("tenantId", contract.tenantId())
                .addValue("serviceId", contract.serviceId())
                .addValue("specType", contract.specType())
                .addValue("createdAt", Timestamp.from(contract.createdAt()))
                .addValue("updatedAt", Timestamp.from(contract.updatedAt())));
    }

    @Override
    public Optional<ApiContract> findLive(UUID tenantId, UUID id) {
        return findOne("""
                SELECT * FROM api_contracts
                WHERE tenant_id = :tenantId AND id = :id AND deleted_at IS NULL
                """, tenantId, id);
    }

    @Override
    public Optional<ApiContract> lockLive(UUID tenantId, UUID id) {
        return findOne("""
                SELECT * FROM api_contracts
                WHERE tenant_id = :tenantId AND id = :id AND deleted_at IS NULL
                FOR UPDATE
                """, tenantId, id);
    }

    @Override
    public Optional<ApiContract> findLiveByService(UUID tenantId, UUID serviceId) {
        return jdbc.query("""
                SELECT * FROM api_contracts
                WHERE tenant_id = :tenantId AND service_id = :serviceId AND deleted_at IS NULL
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("serviceId", serviceId), MAPPER).stream().findFirst();
    }

    @Override
    public void touch(UUID tenantId, UUID id, Instant updatedAt) {
        jdbc.update("""
                UPDATE api_contracts SET updated_at = :updatedAt
                WHERE tenant_id = :tenantId AND id = :id
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("id", id)
                .addValue("updatedAt", Timestamp.from(updatedAt)));
    }

    @Override
    public List<UUID> softDeleteByService(UUID tenantId, UUID serviceId, Instant deletedAt) {
        return jdbc.queryForList("""
                UPDATE api_contracts SET deleted_at = :deletedAt, updated_at = :deletedAt
                WHERE tenant_id = :tenantId AND service_id = :serviceId AND deleted_at IS NULL
                RETURNING id
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("serviceId", serviceId)
                .addValue("deletedAt", Timestamp.from(deletedAt)), UUID.class);
    }

    private Optional<ApiContract> findOne(String sql, UUID tenantId, UUID id) {
        return jdbc.query(sql, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("id", id), MAPPER).stream().findFirst();
    }
}
