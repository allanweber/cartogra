package io.cartogra.web.audit;

import io.cartogra.common.audit.AuditEvent;
import io.cartogra.common.audit.AuditEventPort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;

/**
 * Writes straight into registry's {@code audit_events} on the caller's connection, so the row commits or rolls
 * back with the mutation. Only for services on the registry schema (registry, gateway). A repeated
 * {@code event_id} is ignored, which makes Kafka redelivery safe.
 */
public class JdbcAuditEventAdapter implements AuditEventPort {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcAuditEventAdapter(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public void record(AuditEvent event) {
        jdbc.update("""
                INSERT INTO audit_events
                    (event_id, tenant_id, entity_type, entity_id, action, actor_kind, actor_id, payload, occurred_at)
                VALUES
                    (:eventId, :tenantId, :entityType, :entityId, :action, :actorKind, :actorId,
                     CAST(:payload AS JSONB), :occurredAt)
                ON CONFLICT (event_id) DO NOTHING
                """, new MapSqlParameterSource()
                .addValue("eventId", event.eventId())
                .addValue("tenantId", event.tenantId())
                .addValue("entityType", event.entityType())
                .addValue("entityId", event.entityId())
                .addValue("action", event.action())
                .addValue("actorKind", event.actorKind().toDbValue())
                .addValue("actorId", event.actorId())
                .addValue("payload", objectMapper.writeValueAsString(event.payload()))
                .addValue("occurredAt", Timestamp.from(event.occurredAt())));
    }
}
