package io.cartogra.contract.infrastructure.jdbc;

import io.cartogra.contract.domain.ContractVersion;
import io.cartogra.contract.domain.VersionHeader;
import io.cartogra.contract.repository.ContractVersionRepository;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcContractVersionRepository implements ContractVersionRepository {

    private static final RowMapper<ContractVersion> MAPPER = (rs, _) -> new ContractVersion(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("contract_id", UUID.class),
            rs.getInt("version_number"),
            rs.getString("label"),
            rs.getString("spec_raw"),
            rs.getString("spec_canonical"),
            rs.getString("spec_hash"),
            rs.getObject("is_breaking", Boolean.class),
            rs.getTimestamp("created_at").toInstant(),
            rs.getString("source"),
            rs.getObject("discovered_spec_id", UUID.class),
            rs.getString("repository_full_name"),
            rs.getString("file_path"));

    private static final RowMapper<VersionHeader> HEADER_MAPPER = (rs, _) -> new VersionHeader(
            rs.getObject("id", UUID.class),
            rs.getObject("contract_id", UUID.class),
            rs.getInt("version_number"),
            rs.getString("label"),
            rs.getString("spec_hash"),
            rs.getObject("is_breaking", Boolean.class),
            rs.getTimestamp("created_at").toInstant(),
            rs.getString("source"),
            rs.getString("repository_full_name"),
            rs.getString("file_path"));

    private static final String SELECT_FROM = """
            SELECT v.*, d.repository_full_name, d.file_path
            FROM contract_versions v
            LEFT JOIN discovered_specs d ON d.tenant_id = v.tenant_id AND d.id = v.discovered_spec_id
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcContractVersionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(ContractVersion version) {
        jdbc.update("""
                INSERT INTO contract_versions
                    (id, tenant_id, contract_id, version_number, label, spec_raw, spec_canonical, spec_hash, is_breaking,
                     created_at, source, discovered_spec_id)
                VALUES
                    (:id, :tenantId, :contractId, :versionNumber, :label, :specRaw, :specCanonical, :specHash,
                     :isBreaking, :createdAt, :source, :discoveredSpecId)
                """, new MapSqlParameterSource()
                .addValue("id", version.id())
                .addValue("tenantId", version.tenantId())
                .addValue("contractId", version.contractId())
                .addValue("versionNumber", version.versionNumber())
                .addValue("label", version.label())
                .addValue("specRaw", version.specRaw())
                .addValue("specCanonical", version.specCanonical())
                .addValue("specHash", version.specHash())
                .addValue("isBreaking", version.breaking())
                .addValue("createdAt", Timestamp.from(version.createdAt()))
                .addValue("source", version.source())
                .addValue("discoveredSpecId", version.discoveredSpecId()));
    }

    @Override
    public Optional<ContractVersion> findLatest(UUID tenantId, UUID contractId) {
        return jdbc.query(SELECT_FROM + """
                WHERE v.tenant_id = :tenantId AND v.contract_id = :contractId AND v.deleted_at IS NULL
                ORDER BY v.version_number DESC
                LIMIT 1
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("contractId", contractId), MAPPER).stream().findFirst();
    }

    @Override
    public int softDeleteByContracts(UUID tenantId, Collection<UUID> contractIds, Instant deletedAt) {
        if (contractIds.isEmpty()) {
            return 0;
        }
        return jdbc.update("""
                UPDATE contract_versions SET deleted_at = :deletedAt
                WHERE tenant_id = :tenantId AND contract_id IN (:contractIds) AND deleted_at IS NULL
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("contractIds", contractIds)
                .addValue("deletedAt", Timestamp.from(deletedAt)));
    }

    @Override
    public Optional<ContractVersion> findByNumber(UUID tenantId, UUID contractId, int versionNumber) {
        return jdbc.query(SELECT_FROM + """
                WHERE v.tenant_id = :tenantId AND v.contract_id = :contractId AND v.version_number = :versionNumber
                  AND v.deleted_at IS NULL
                """, params(tenantId, contractId).addValue("versionNumber", versionNumber), MAPPER)
                .stream().findFirst();
    }

    @Override
    public List<VersionHeader> list(UUID tenantId, UUID contractId, int limit, int offset) {
        return jdbc.query("""
                SELECT v.id, v.contract_id, v.version_number, v.label, v.spec_hash, v.is_breaking, v.created_at,
                       v.source, d.repository_full_name, d.file_path
                FROM contract_versions v
                LEFT JOIN discovered_specs d ON d.tenant_id = v.tenant_id AND d.id = v.discovered_spec_id
                WHERE v.tenant_id = :tenantId AND v.contract_id = :contractId AND v.deleted_at IS NULL
                ORDER BY v.version_number DESC
                LIMIT :limit OFFSET :offset
                """, params(tenantId, contractId).addValue("limit", limit).addValue("offset", offset), HEADER_MAPPER);
    }

    @Override
    public long count(UUID tenantId, UUID contractId) {
        Long count = jdbc.queryForObject("""
                SELECT count(*) FROM contract_versions
                WHERE tenant_id = :tenantId AND contract_id = :contractId AND deleted_at IS NULL
                """, params(tenantId, contractId), Long.class);
        return count == null ? 0 : count;
    }

    private static MapSqlParameterSource params(UUID tenantId, UUID contractId) {
        return new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("contractId", contractId);
    }
}
