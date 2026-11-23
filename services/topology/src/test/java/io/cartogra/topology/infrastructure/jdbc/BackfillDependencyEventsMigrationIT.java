package io.cartogra.topology.infrastructure.jdbc;

import db.migration.V017__BackfillDependencyEvents;
import io.cartogra.common.event.EventEnvelope;
import io.cartogra.topology.AbstractTopologyIT;
import io.cartogra.topology.domain.event.DependencyChangedPayload;
import io.cartogra.topology.infrastructure.kafka.DependencyEventPublisher;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The one-time backfill puts one live event per live edge in the outbox, readable as a live envelope. */
class BackfillDependencyEventsMigrationIT extends AbstractTopologyIT {

    private static final TypeReference<EventEnvelope<DependencyChangedPayload>> ENVELOPE = new TypeReference<>() {
    };

    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired ObjectMapper objectMapper;

    @Test
    void writesOneEventPerLiveEdgeAndSkipsRemovedEdges() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID declared = insertEdge(tenant, "declared", "http", false);
        UUID observed = insertEdge(tenant, "observed", "grpc", false);
        insertEdge(tenant, "declared", "kafka", true);

        try (Connection connection = dataSource.getConnection()) {
            Context context = mock(Context.class);
            when(context.getConnection()).thenReturn(connection);
            new V017__BackfillDependencyEvents().migrate(context);
        }

        List<EventEnvelope<DependencyChangedPayload>> events = jdbc.query(
                "SELECT topic, record_key, payload FROM outbox WHERE tenant_id = ? ORDER BY id", (rs, _) -> {
                    assertThat(rs.getString("topic")).isEqualTo(DependencyEventPublisher.TOPIC);
                    var envelope = objectMapper.readValue(rs.getString("payload"), ENVELOPE);
                    assertThat(rs.getString("record_key")).isEqualTo(envelope.entityId().toString());
                    return envelope;
                }, tenant);

        assertThat(events).extracting(EventEnvelope::eventType)
                .containsExactlyInAnyOrder("dependency.declared", "dependency.observed");
        assertThat(events).extracting(e -> e.payload().edgeId()).containsExactlyInAnyOrder(declared, observed);
        assertThat(events).allSatisfy(e -> {
            assertThat(e.tenantId()).isEqualTo(tenant);
            assertThat(e.entityId()).isEqualTo(e.payload().edgeId());
        });
    }

    private UUID insertEdge(UUID tenant, String type, String protocol, boolean deleted) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO dependencies (id, tenant_id, source_service_id, target_service_id, dependency_type,
                                          protocol, deleted_at)
                VALUES (?, ?, gen_random_uuid(), gen_random_uuid(), ?, ?, CASE WHEN ? THEN now() END)
                """, id, tenant, type, protocol, deleted);
        return id;
    }
}
