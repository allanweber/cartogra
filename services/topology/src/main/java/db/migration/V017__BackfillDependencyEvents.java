package db.migration;

import io.cartogra.common.event.EventEnvelope;
import io.cartogra.topology.domain.event.DependencyChangedPayload;
import io.cartogra.topology.infrastructure.kafka.DependencyEventPublisher;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Sends every edge that was live before the outbox existed, once: Contract builds its consumer list only from
 * {@code dependency.changed} events, so without this it would never hear about those edges. Runs with
 * {@code app.rls_bypass} on (the Flyway init SQL), so it sees every tenant.
 */
public class V017__BackfillDependencyEvents extends BaseJavaMigration {

    private static final int BATCH = 500;

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @Override
    public void migrate(Context context) throws SQLException {
        Connection connection = context.getConnection();
        try (PreparedStatement select = connection.prepareStatement("""
                SELECT id, tenant_id, source_service_id, target_service_id, dependency_type, protocol
                FROM dependencies
                WHERE deleted_at IS NULL
                ORDER BY created_at, id
                """);
             PreparedStatement insert = connection.prepareStatement("""
                     INSERT INTO outbox (tenant_id, topic, record_key, payload, headers)
                     VALUES (?, ?, ?, ?, '{}'::jsonb)
                     """);
             ResultSet rows = select.executeQuery()) {
            int pending = 0;
            while (rows.next()) {
                var payload = new DependencyChangedPayload(
                        rows.getObject("id", UUID.class),
                        rows.getObject("source_service_id", UUID.class),
                        rows.getObject("target_service_id", UUID.class),
                        rows.getString("dependency_type"),
                        rows.getString("protocol"));
                UUID tenantId = rows.getObject("tenant_id", UUID.class);
                String eventType = "declared".equals(payload.dependencyType())
                        ? DependencyEventPublisher.DECLARED
                        : DependencyEventPublisher.OBSERVED;
                var envelope = EventEnvelope.of(eventType, payload.edgeId(), tenantId, 1, payload);
                insert.setObject(1, tenantId);
                insert.setString(2, DependencyEventPublisher.TOPIC);
                insert.setString(3, payload.edgeId().toString());
                insert.setString(4, objectMapper.writeValueAsString(envelope));
                insert.addBatch();
                if (++pending == BATCH) {
                    insert.executeBatch();
                    pending = 0;
                }
            }
            if (pending > 0) {
                insert.executeBatch();
            }
        }
    }
}
