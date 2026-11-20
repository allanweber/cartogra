package io.cartogra.contract.infrastructure.jdbc;

import io.cartogra.contract.domain.DiscoveredSpec;
import io.cartogra.contract.domain.DiscoveredSpecStatus;
import io.cartogra.contract.repository.DiscoveredSpecRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcDiscoveredSpecRepository implements DiscoveredSpecRepository {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final int MAX_RECORD_ATTEMPTS = 3;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final RowMapper<DiscoveredSpec> mapper;

    public JdbcDiscoveredSpecRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.mapper = (rs, _) -> new DiscoveredSpec(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("connection_id", UUID.class),
                rs.getString("repository_full_name"),
                rs.getString("file_path"),
                rs.getString("content_sha256"),
                rs.getString("spec_raw"),
                DiscoveredSpecStatus.valueOf(rs.getString("status")),
                rs.getObject("service_id", UUID.class),
                rs.getObject("contract_version_id", UUID.class),
                readErrors(rs.getString("errors")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    @Override
    public DiscoveredSpec recordLatest(DiscoveredSpec spec) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", spec.id())
                .addValue("tenantId", spec.tenantId())
                .addValue("connectionId", spec.connectionId())
                .addValue("repositoryFullName", spec.repositoryFullName())
                .addValue("filePath", spec.filePath())
                .addValue("contentSha256", spec.contentSha256())
                .addValue("specRaw", spec.specRaw());
        for (int attempt = 0; attempt < MAX_RECORD_ATTEMPTS; attempt++) {
            Optional<DiscoveredSpec> latest = jdbc.query("""
                    SELECT * FROM discovered_specs
                    WHERE tenant_id = :tenantId AND repository_full_name = :repositoryFullName
                      AND file_path = :filePath AND is_latest
                    FOR UPDATE
                    """, params, mapper).stream().findFirst();
            if (latest.isPresent() && latest.get().contentSha256().equals(spec.contentSha256())) {
                return latest.get();
            }
            latest.ifPresent(previous -> jdbc.update("""
                    UPDATE discovered_specs
                    SET is_latest = false,
                        status = CASE WHEN status = 'PENDING' THEN 'SUPERSEDED' ELSE status END,
                        updated_at = now()
                    WHERE tenant_id = :tenantId AND id = :previousId
                    """, new MapSqlParameterSource()
                    .addValue("tenantId", spec.tenantId())
                    .addValue("previousId", previous.id())));
            List<DiscoveredSpec> inserted = jdbc.query("""
                    INSERT INTO discovered_specs
                        (id, tenant_id, connection_id, repository_full_name, file_path, content_sha256, spec_raw)
                    VALUES
                        (:id, :tenantId, :connectionId, :repositoryFullName, :filePath, :contentSha256, :specRaw)
                    ON CONFLICT (tenant_id, repository_full_name, file_path) WHERE is_latest DO NOTHING
                    RETURNING *
                    """, params, mapper);
            if (!inserted.isEmpty()) {
                return inserted.getFirst();
            }
        }
        throw new IllegalStateException("Could not record the spec " + spec.filePath() + " of "
                + spec.repositoryFullName() + ": the latest row changed on each attempt");
    }

    @Override
    public DiscoveredSpec reopenLatest(DiscoveredSpec latest) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", latest.tenantId())
                .addValue("latestId", latest.id())
                .addValue("id", UUID.randomUUID());
        jdbc.update("""
                UPDATE discovered_specs SET is_latest = false, updated_at = now()
                WHERE tenant_id = :tenantId AND id = :latestId AND is_latest
                """, params);
        return jdbc.query("""
                INSERT INTO discovered_specs
                    (id, tenant_id, connection_id, repository_full_name, file_path, content_sha256, spec_raw)
                SELECT :id, tenant_id, connection_id, repository_full_name, file_path, content_sha256, spec_raw
                FROM discovered_specs
                WHERE tenant_id = :tenantId AND id = :latestId
                RETURNING *
                """, params, mapper).getFirst();
    }

    @Override
    public List<DiscoveredSpec> findPending(UUID tenantId, String repositoryFullName) {
        return jdbc.query("""
                SELECT * FROM discovered_specs
                WHERE tenant_id = :tenantId AND repository_full_name = :repositoryFullName
                  AND status = 'PENDING' AND deleted_at IS NULL
                ORDER BY created_at, id
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("repositoryFullName", repositoryFullName), mapper);
    }

    @Override
    public Optional<DiscoveredSpec> lockPending(UUID tenantId, UUID id) {
        return jdbc.query("""
                SELECT * FROM discovered_specs
                WHERE tenant_id = :tenantId AND id = :id AND status = 'PENDING'
                FOR UPDATE
                """, new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("id", id), mapper)
                .stream().findFirst();
    }

    @Override
    public void markStatus(UUID tenantId, UUID id, DiscoveredSpecStatus status, @Nullable UUID serviceId,
                           @Nullable UUID contractVersionId, List<String> errors) {
        jdbc.update("""
                UPDATE discovered_specs
                SET status = :status, service_id = :serviceId, contract_version_id = :contractVersionId,
                    errors = CAST(:errors AS JSONB), updated_at = now()
                WHERE tenant_id = :tenantId AND id = :id AND status = 'PENDING'
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("id", id)
                .addValue("status", status.name())
                .addValue("serviceId", serviceId)
                .addValue("contractVersionId", contractVersionId)
                .addValue("errors", errors.isEmpty() ? null : objectMapper.writeValueAsString(errors)));
    }

    private List<String> readErrors(@Nullable String json) {
        return json == null ? List.of() : objectMapper.readValue(json, STRING_LIST);
    }
}
