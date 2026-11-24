package io.cartogra.contract.infrastructure.jdbc;

import io.cartogra.contract.domain.CompatibilityChange;
import io.cartogra.contract.domain.ContractCheck;
import io.cartogra.contract.repository.ContractCheckRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
public class JdbcContractCheckRepository implements ContractCheckRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcContractCheckRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public void insert(ContractCheck check) {
        jdbc.update("""
                INSERT INTO contract_checks (id, tenant_id, service_id, contract_id, baseline_version_id,
                                             candidate_hash, is_breaking, changes, affected_consumer_ids,
                                             api_key_id, created_at)
                VALUES (:id, :tenantId, :serviceId, :contractId, :baselineVersionId, :candidateHash, :breaking,
                        CAST(:changes AS jsonb), CAST(:consumerIds AS uuid[]), :apiKeyId, :createdAt)
                """, new MapSqlParameterSource()
                .addValue("id", check.id())
                .addValue("tenantId", check.tenantId())
                .addValue("serviceId", check.serviceId())
                .addValue("contractId", check.contractId())
                .addValue("baselineVersionId", check.baselineVersionId())
                .addValue("candidateHash", check.candidateHash())
                .addValue("breaking", check.breaking())
                .addValue("changes", objectMapper.writeValueAsString(check.changes().stream().map(
                        JdbcContractCheckRepository::toJson).toList()))
                .addValue("consumerIds", uuidArray(check.affectedConsumerIds()))
                .addValue("apiKeyId", check.apiKeyId())
                .addValue("createdAt", Timestamp.from(check.createdAt())));
    }

    @Override
    public long countByService(UUID tenantId, UUID serviceId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM contract_checks WHERE tenant_id = :tenantId AND service_id = :serviceId",
                new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("serviceId", serviceId),
                Long.class);
        return count == null ? 0 : count;
    }

    private static Map<String, Object> toJson(CompatibilityChange c) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("rule", c.rule().id());
        json.put("breaking", c.breaking());
        json.put("direction", c.direction().name().toLowerCase(Locale.ROOT));
        json.put("method", c.method());
        json.put("path", c.path());
        json.put("location", c.location());
        json.put("message", c.message());
        return json;
    }

    private static String uuidArray(List<UUID> ids) {
        return ids.stream().map(UUID::toString).collect(Collectors.joining(",", "{", "}"));
    }
}
