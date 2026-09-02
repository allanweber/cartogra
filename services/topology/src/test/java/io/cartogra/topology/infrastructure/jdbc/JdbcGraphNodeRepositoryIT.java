package io.cartogra.topology.infrastructure.jdbc;

import io.cartogra.topology.AbstractTopologyIT;
import io.cartogra.topology.domain.GraphNode;
import io.cartogra.topology.repository.GraphNodeRepository;
import io.cartogra.topology.repository.GraphNodeUpsert;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcGraphNodeRepositoryIT extends AbstractTopologyIT {

    private static final UUID TENANT = UUID.randomUUID();

    @Autowired
    private GraphNodeRepository repository;

    @Test
    void upsertCreatesNode() {
        UUID serviceId = UUID.randomUUID();

        repository.upsert(new GraphNodeUpsert(TENANT, serviceId, "payments", null, "STANDARD", "HEALTHY"));

        Optional<GraphNode> found = repository.findByServiceId(TENANT, serviceId);
        assertThat(found).isPresent();
        assertThat(found.get().name()).isEqualTo("payments");
        assertThat(found.get().tier()).isEqualTo("STANDARD");
        assertThat(found.get().healthStatus()).isEqualTo("HEALTHY");
        assertThat(found.get().isDeleted()).isFalse();
    }

    @Test
    void upsertOnExistingRowUpdatesFieldsInPlace() {
        UUID serviceId = UUID.randomUUID();
        UUID team = UUID.randomUUID();
        repository.upsert(new GraphNodeUpsert(TENANT, serviceId, "payments", null, "STANDARD", "HEALTHY"));

        repository.upsert(new GraphNodeUpsert(TENANT, serviceId, "payments-v2", team, "CRITICAL", "DEGRADED"));

        Optional<GraphNode> found = repository.findByServiceId(TENANT, serviceId);
        assertThat(found).isPresent();
        assertThat(found.get().name()).isEqualTo("payments-v2");
        assertThat(found.get().teamId()).isEqualTo(team);
        assertThat(found.get().tier()).isEqualTo("CRITICAL");
        assertThat(found.get().healthStatus()).isEqualTo("DEGRADED");
    }

    @Test
    void softDeleteSetsDeletedAt() {
        UUID serviceId = UUID.randomUUID();
        repository.upsert(new GraphNodeUpsert(TENANT, serviceId, "payments", null, null, "HEALTHY"));
        Instant deletedAt = Instant.parse("2026-08-01T00:00:00Z");

        repository.softDelete(TENANT, serviceId, deletedAt);

        Optional<GraphNode> found = repository.findByServiceId(TENANT, serviceId);
        assertThat(found).isPresent();
        assertThat(found.get().isDeleted()).isTrue();
        assertThat(found.get().deletedAt()).isEqualTo(deletedAt);
    }

    @Test
    void upsertAfterSoftDeleteRevivesTheNode() {
        UUID serviceId = UUID.randomUUID();
        repository.upsert(new GraphNodeUpsert(TENANT, serviceId, "payments", null, null, "HEALTHY"));
        repository.softDelete(TENANT, serviceId, Instant.now());

        repository.upsert(new GraphNodeUpsert(TENANT, serviceId, "payments", null, null, "HEALTHY"));

        Optional<GraphNode> found = repository.findByServiceId(TENANT, serviceId);
        assertThat(found).isPresent();
        assertThat(found.get().isDeleted()).isFalse();
    }
}
