package io.cartogra.contract.infrastructure.jdbc;

import io.cartogra.contract.domain.ApiContract;
import io.cartogra.contract.domain.ContractSummary;
import io.cartogra.contract.domain.VersionHeader;
import org.jspecify.annotations.Nullable;
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

    private static final RowMapper<ContractSummary> SUMMARY_MAPPER = (rs, _) -> new ContractSummary(
            rs.getObject("id", UUID.class),
            rs.getObject("service_id", UUID.class),
            rs.getString("service_name"),
            new VersionHeader(
                    rs.getObject("version_id", UUID.class),
                    rs.getObject("id", UUID.class),
                    rs.getInt("version_number"),
                    rs.getString("label"),
                    rs.getString("spec_hash"),
                    rs.getObject("is_breaking", Boolean.class),
                    rs.getTimestamp("version_created_at").toInstant(),
                    rs.getString("source"),
                    rs.getString("repository_full_name"),
                    rs.getString("file_path")),
            rs.getTimestamp("updated_at").toInstant());

    private static final String LIVE_FROM = """
            FROM api_contracts c
            LEFT JOIN services s ON s.tenant_id = c.tenant_id AND s.service_id = c.service_id
            CROSS JOIN LATERAL (
                SELECT id, version_number, label, spec_hash, is_breaking, created_at, source, discovered_spec_id
                FROM contract_versions
                WHERE tenant_id = c.tenant_id AND contract_id = c.id AND deleted_at IS NULL
                ORDER BY version_number DESC LIMIT 1
            ) v
            LEFT JOIN discovered_specs d ON d.tenant_id = c.tenant_id AND d.id = v.discovered_spec_id
            WHERE c.tenant_id = :tenantId AND c.deleted_at IS NULL
            """;

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

    @Override
    public List<ContractSummary> listLive(UUID tenantId, @Nullable String q, @Nullable Boolean breaking, int limit,
                                          int offset) {
        MapSqlParameterSource params = filterParams(tenantId, q, breaking)
                .addValue("limit", limit)
                .addValue("offset", offset);
        return jdbc.query("""
                SELECT c.id, c.service_id, c.updated_at, s.name AS service_name,
                       v.id AS version_id, v.version_number, v.label, v.spec_hash, v.is_breaking,
                       v.created_at AS version_created_at, v.source, d.repository_full_name, d.file_path
                """ + LIVE_FROM + filterClause(q, breaking) + """
                ORDER BY c.updated_at DESC, c.id
                LIMIT :limit OFFSET :offset
                """, params, SUMMARY_MAPPER);
    }

    @Override
    public long countLive(UUID tenantId, @Nullable String q, @Nullable Boolean breaking) {
        Long count = jdbc.queryForObject("SELECT count(*) " + LIVE_FROM + filterClause(q, breaking),
                filterParams(tenantId, q, breaking), Long.class);
        return count == null ? 0 : count;
    }

    private static String filterClause(@Nullable String q, @Nullable Boolean breaking) {
        return (q == null || q.isBlank() ? "" : " AND s.name ILIKE :q\n")
                + (breaking == null ? "" : " AND v.is_breaking = :breaking\n");
    }

    private static MapSqlParameterSource filterParams(UUID tenantId, @Nullable String q, @Nullable Boolean breaking) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("tenantId", tenantId);
        if (q != null && !q.isBlank()) {
            params.addValue("q", "%" + q.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        if (breaking != null) {
            params.addValue("breaking", breaking);
        }
        return params;
    }

    private Optional<ApiContract> findOne(String sql, UUID tenantId, UUID id) {
        return jdbc.query(sql, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("id", id), MAPPER).stream().findFirst();
    }
}
