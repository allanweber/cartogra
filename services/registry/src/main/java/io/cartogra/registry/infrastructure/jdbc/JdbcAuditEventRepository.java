package io.cartogra.registry.infrastructure.jdbc;

import io.cartogra.common.audit.ActorKind;
import io.cartogra.common.audit.AuditEvent;
import io.cartogra.registry.domain.AuditEntry;
import io.cartogra.registry.repository.AuditEventFilter;
import io.cartogra.registry.repository.AuditEventRepository;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class JdbcAuditEventRepository implements AuditEventRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcAuditEventRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<AuditEntry> findAll(UUID tenantId, AuditEventFilter filter, int limit, int offset) {
        var params = params(tenantId, filter).addValue("limit", limit).addValue("offset", offset);
        String sql = """
                SELECT a.*, COALESCE(u.name, u.email) AS actor_name
                FROM audit_events a
                LEFT JOIN users u ON u.id = a.actor_id AND u.tenant_id = a.tenant_id
                """ + where(filter, "a.") + " ORDER BY a.occurred_at DESC, a.id DESC LIMIT :limit OFFSET :offset";
        return jdbc.query(sql, params, mapper());
    }

    @Override
    public long count(UUID tenantId, AuditEventFilter filter) {
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM audit_events " + where(filter, ""),
                params(tenantId, filter), Long.class);
        return total == null ? 0 : total;
    }

    private static String where(AuditEventFilter filter, String alias) {
        var sql = new StringBuilder("WHERE ").append(alias).append("tenant_id = :tenantId");
        if (filter.entityTypes() != null && !filter.entityTypes().isEmpty()) {
            sql.append(" AND ").append(alias).append("entity_type IN (:entityTypes)");
        }
        if (filter.entityId() != null) sql.append(" AND ").append(alias).append("entity_id = :entityId");
        if (filter.actorKind() != null) sql.append(" AND ").append(alias).append("actor_kind = :actorKind");
        if (filter.from() != null) sql.append(" AND ").append(alias).append("occurred_at >= :from");
        if (filter.to() != null) sql.append(" AND ").append(alias).append("occurred_at < :to");
        return sql.toString();
    }

    private static MapSqlParameterSource params(UUID tenantId, AuditEventFilter filter) {
        return new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("entityTypes", filter.entityTypes())
                .addValue("entityId", filter.entityId())
                .addValue("actorKind", filter.actorKind() != null ? filter.actorKind().toDbValue() : null)
                .addValue("from", filter.from() != null ? Timestamp.from(filter.from()) : null)
                .addValue("to", filter.to() != null ? Timestamp.from(filter.to()) : null);
    }

    private RowMapper<AuditEntry> mapper() {
        return (rs, _) -> new AuditEntry(new AuditEvent(
                rs.getObject("event_id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getString("entity_type"),
                rs.getObject("entity_id", UUID.class),
                rs.getString("action"),
                ActorKind.fromDbValue(rs.getString("actor_kind")),
                rs.getObject("actor_id", UUID.class),
                objectMapper.readValue(rs.getString("payload"), MAP_TYPE),
                rs.getTimestamp("occurred_at").toInstant()),
                rs.getString("actor_name"));
    }
}
