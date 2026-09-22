package io.cartogra.topology.infrastructure.jdbc;

import io.cartogra.topology.AbstractTopologyIT;
import io.cartogra.topology.domain.GraphNode;
import io.cartogra.topology.repository.GraphNodeRepository;
import io.cartogra.topology.repository.GraphNodeUpsert;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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

    @Test
    void findForGraphReturnsLiveNodesOrderedByNameScopedToTenant() {
        UUID tenantId = UUID.randomUUID();
        UUID otherTenant = UUID.randomUUID();
        repository.upsert(new GraphNodeUpsert(tenantId, UUID.randomUUID(), "zebra", null, null, "HEALTHY"));
        repository.upsert(new GraphNodeUpsert(tenantId, UUID.randomUUID(), "alpha", null, null, "HEALTHY"));
        repository.upsert(new GraphNodeUpsert(otherTenant, UUID.randomUUID(), "aardvark", null, null, "HEALTHY"));
        UUID deletedService = UUID.randomUUID();
        repository.upsert(new GraphNodeUpsert(tenantId, deletedService, "deleted-svc", null, null, "HEALTHY"));
        repository.softDelete(tenantId, deletedService, Instant.now());

        List<GraphNode> found = repository.findForGraph(tenantId, null, 10);

        assertThat(found).extracting(GraphNode::name).containsExactly("alpha", "zebra");
    }

    @Test
    void findForGraphScopesToTeamWhenProvided() {
        UUID tenantId = UUID.randomUUID();
        UUID team = UUID.randomUUID();
        repository.upsert(new GraphNodeUpsert(tenantId, UUID.randomUUID(), "team-svc", team, null, "HEALTHY"));
        repository.upsert(new GraphNodeUpsert(tenantId, UUID.randomUUID(), "other-svc", null, null, "HEALTHY"));

        List<GraphNode> found = repository.findForGraph(tenantId, team, 10);

        assertThat(found).extracting(GraphNode::name).containsExactly("team-svc");
    }

    @Test
    void findForGraphRespectsLimit() {
        UUID tenantId = UUID.randomUUID();
        repository.upsert(new GraphNodeUpsert(tenantId, UUID.randomUUID(), "a", null, null, "HEALTHY"));
        repository.upsert(new GraphNodeUpsert(tenantId, UUID.randomUUID(), "b", null, null, "HEALTHY"));

        List<GraphNode> found = repository.findForGraph(tenantId, null, 1);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).name()).isEqualTo("a");
    }

    @Test
    void findByServiceIdsReturnsOnlyLiveMatchesOrderedByName() {
        UUID tenantId = UUID.randomUUID();
        UUID wanted1 = UUID.randomUUID();
        UUID wanted2 = UUID.randomUUID();
        UUID notWanted = UUID.randomUUID();
        UUID deletedWanted = UUID.randomUUID();
        repository.upsert(new GraphNodeUpsert(tenantId, wanted1, "zebra", null, null, "HEALTHY"));
        repository.upsert(new GraphNodeUpsert(tenantId, wanted2, "alpha", null, null, "HEALTHY"));
        repository.upsert(new GraphNodeUpsert(tenantId, notWanted, "unwanted", null, null, "HEALTHY"));
        repository.upsert(new GraphNodeUpsert(tenantId, deletedWanted, "deleted", null, null, "HEALTHY"));
        repository.softDelete(tenantId, deletedWanted, Instant.now());

        List<GraphNode> found = repository.findByServiceIds(tenantId, Set.of(wanted1, wanted2, deletedWanted), 10);

        assertThat(found).extracting(GraphNode::name).containsExactly("alpha", "zebra");
    }

    @Test
    void findByServiceIdsWithEmptyCollectionReturnsEmptyWithoutQuerying() {
        assertThat(repository.findByServiceIds(UUID.randomUUID(), Set.of(), 10)).isEmpty();
    }
}
