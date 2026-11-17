package io.cartogra.web.audit;

import io.cartogra.common.audit.AuditEvent;
import io.cartogra.common.audit.AuditEventPort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.util.List;
import java.util.StringJoiner;

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
        recordAll(List.of(event));
    }

    @Override
    public void recordAll(List<AuditEvent> events) {
        if (events.isEmpty()) {
            return;
        }
        var params = new MapSqlParameterSource();
        var rows = new StringJoiner(", ");
        for (int i = 0; i < events.size(); i++) {
            AuditEvent event = events.get(i);
            rows.add(("(:eventId%1$d, :tenantId%1$d, :entityType%1$d, :entityId%1$d, :action%1$d, :actorKind%1$d, "
                    + ":actorId%1$d, CAST(:payload%1$d AS JSONB), :occurredAt%1$d)").formatted(i));
            params.addValue("eventId" + i, event.eventId())
                    .addValue("tenantId" + i, event.tenantId())
                    .addValue("entityType" + i, event.entityType())
                    .addValue("entityId" + i, event.entityId())
                    .addValue("action" + i, event.action())
                    .addValue("actorKind" + i, event.actorKind().toDbValue())
                    .addValue("actorId" + i, event.actorId())
                    .addValue("payload" + i, objectMapper.writeValueAsString(event.payload()))
                    .addValue("occurredAt" + i, Timestamp.from(event.occurredAt()));
        }
        jdbc.update("""
                INSERT INTO audit_events
                    (event_id, tenant_id, entity_type, entity_id, action, actor_kind, actor_id, payload, occurred_at)
                VALUES %s
                ON CONFLICT (event_id) DO NOTHING
                """.formatted(rows), params);
    }
}
