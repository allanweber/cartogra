package io.cartogra.topology.infrastructure.jdbc;

import io.cartogra.topology.AbstractTopologyIT;
import io.cartogra.topology.domain.Dependency;
import io.cartogra.topology.domain.DependencyProtocol;
import io.cartogra.topology.domain.DependencyType;
import io.cartogra.topology.domain.GraphEdge;
import io.cartogra.topology.repository.DependencyGraphViewRepository;
import io.cartogra.topology.repository.DependencyRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers reads against {@code dependency_graph_edges} for the graph API — separate from
 * {@link DependencyGraphViewRefreshIT}, which only covers the refresh path itself. Every test
 * calls {@link DependencyGraphViewRepository#refresh()} after seeding, since the view is only
 * ever updated by an explicit refresh (see {@code DependencyGraphViewRefreshScheduler}).
 */
class JdbcDependencyGraphViewRepositoryIT extends AbstractTopologyIT {

    @Autowired
    private DependencyRepository dependencyRepository;

    @Autowired
    private DependencyGraphViewRepository graphViewRepository;

    private Dependency saveDependency(UUID tenantId, UUID source, UUID target, DependencyType type, DependencyProtocol protocol) {
        Instant now = Instant.now();
        return dependencyRepository.save(new Dependency(UUID.randomUUID(), tenantId, source, target, type, protocol, null, now, now, null));
    }

    @Test
    void findByServiceIdsMatchesEitherSourceOrTarget() {
        UUID tenantId = UUID.randomUUID();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        saveDependency(tenantId, a, b, DependencyType.DECLARED, DependencyProtocol.HTTP);
        saveDependency(tenantId, c, a, DependencyType.DECLARED, DependencyProtocol.GRPC);
        saveDependency(tenantId, b, c, DependencyType.DECLARED, DependencyProtocol.KAFKA);
        graphViewRepository.refresh();

        List<GraphEdge> found = graphViewRepository.findByServiceIds(tenantId, Set.of(a), null);

        assertThat(found).extracting(GraphEdge::sourceServiceId, GraphEdge::targetServiceId)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(a, b),
                        org.assertj.core.groups.Tuple.tuple(c, a));
    }

    @Test
    void findByServiceIdsFiltersByType() {
        UUID tenantId = UUID.randomUUID();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        saveDependency(tenantId, a, b, DependencyType.DECLARED, DependencyProtocol.HTTP);
        saveDependency(tenantId, a, c, DependencyType.OBSERVED, DependencyProtocol.HTTP);
        graphViewRepository.refresh();

        List<GraphEdge> found = graphViewRepository.findByServiceIds(tenantId, Set.of(a), DependencyType.OBSERVED);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).targetServiceId()).isEqualTo(c);
    }

    @Test
    void findByServiceIdsIsScopedToTenant() {
        UUID tenantId = UUID.randomUUID();
        UUID otherTenant = UUID.randomUUID();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        saveDependency(otherTenant, a, b, DependencyType.DECLARED, DependencyProtocol.HTTP);
        graphViewRepository.refresh();

        List<GraphEdge> found = graphViewRepository.findByServiceIds(tenantId, Set.of(a), null);

        assertThat(found).isEmpty();
    }

    @Test
    void findByServiceIdsWithEmptyCollectionReturnsEmptyWithoutQuerying() {
        assertThat(graphViewRepository.findByServiceIds(UUID.randomUUID(), Set.of(), null)).isEmpty();
    }

    @Test
    void findByServiceIdsDoesNotSeeUnrefreshedWrites() {
        UUID tenantId = UUID.randomUUID();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        saveDependency(tenantId, a, b, DependencyType.DECLARED, DependencyProtocol.HTTP);

        List<GraphEdge> found = graphViewRepository.findByServiceIds(tenantId, Set.of(a), null);

        assertThat(found).isEmpty();
    }
}
