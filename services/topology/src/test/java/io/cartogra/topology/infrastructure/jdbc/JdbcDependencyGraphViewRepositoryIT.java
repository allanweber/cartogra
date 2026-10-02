package io.cartogra.topology.infrastructure.jdbc;

import io.cartogra.topology.AbstractTopologyIT;
import io.cartogra.topology.domain.BlastRadiusDirection;
import io.cartogra.topology.domain.Dependency;
import io.cartogra.topology.domain.DependencyProtocol;
import io.cartogra.topology.domain.DependencyType;
import io.cartogra.topology.domain.GraphEdge;
import io.cartogra.topology.repository.BlastRadiusRow;
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

    @Test
    void twelveNodeFanOutReturnsExactlyThoseTwelve() {
        UUID tenantId = UUID.randomUUID();
        UUID a = UUID.randomUUID();
        List<UUID> firstHop = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        firstHop.forEach(b -> saveDependency(tenantId, a, b, DependencyType.DECLARED, DependencyProtocol.HTTP));

        java.util.Map<UUID, UUID> secondHopParent = new java.util.LinkedHashMap<>();
        for (UUID b : firstHop) {
            for (int i = 0; i < 2; i++) {
                UUID c = UUID.randomUUID();
                secondHopParent.put(c, b);
                saveDependency(tenantId, b, c, DependencyType.DECLARED, DependencyProtocol.HTTP);
            }
        }
        graphViewRepository.refresh();

        List<BlastRadiusRow> rows = graphViewRepository.findBlastRadius(tenantId, a, BlastRadiusDirection.UPSTREAM, 3);

        assertThat(rows).hasSize(12);
        assertThat(rows).filteredOn(row -> firstHop.contains(row.serviceId()))
                .hasSize(4)
                .allSatisfy(row -> assertThat(row.depth()).isEqualTo(1));
        assertThat(rows).filteredOn(row -> secondHopParent.containsKey(row.serviceId()))
                .hasSize(8)
                .allSatisfy(row -> assertThat(row.depth()).isEqualTo(2));
    }

    @Test
    void threeNodeCycleTerminatesAndDoesNotReturnSelf() {
        UUID tenantId = UUID.randomUUID();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        saveDependency(tenantId, a, b, DependencyType.DECLARED, DependencyProtocol.HTTP);
        saveDependency(tenantId, b, c, DependencyType.DECLARED, DependencyProtocol.HTTP);
        saveDependency(tenantId, c, a, DependencyType.DECLARED, DependencyProtocol.HTTP);
        graphViewRepository.refresh();

        List<BlastRadiusRow> rows = graphViewRepository.findBlastRadius(tenantId, a, BlastRadiusDirection.UPSTREAM, 6);

        assertThat(rows).extracting(BlastRadiusRow::serviceId, BlastRadiusRow::depth)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(b, 1),
                        org.assertj.core.groups.Tuple.tuple(c, 2));
    }

    @Test
    void depthCapPeeksExactlyOneHopBeyondAndNoFurther() {
        UUID tenantId = UUID.randomUUID();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        UUID d = UUID.randomUUID();
        saveDependency(tenantId, a, b, DependencyType.DECLARED, DependencyProtocol.HTTP);
        saveDependency(tenantId, b, c, DependencyType.DECLARED, DependencyProtocol.HTTP);
        saveDependency(tenantId, c, d, DependencyType.DECLARED, DependencyProtocol.HTTP);
        graphViewRepository.refresh();

        List<BlastRadiusRow> rows = graphViewRepository.findBlastRadius(tenantId, a, BlastRadiusDirection.UPSTREAM, 2);

        assertThat(rows).extracting(BlastRadiusRow::serviceId, BlastRadiusRow::depth)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(b, 1),
                        org.assertj.core.groups.Tuple.tuple(c, 2),
                        org.assertj.core.groups.Tuple.tuple(d, 3));
        assertThat(rows).extracting(BlastRadiusRow::depth).allSatisfy(depth -> assertThat(depth).isLessThanOrEqualTo(3));
    }

    @Test
    void downstreamIsTheMirrorOfUpstreamOnTheSameCycle() {
        UUID tenantId = UUID.randomUUID();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        saveDependency(tenantId, a, b, DependencyType.DECLARED, DependencyProtocol.HTTP);
        saveDependency(tenantId, b, c, DependencyType.DECLARED, DependencyProtocol.HTTP);
        saveDependency(tenantId, c, a, DependencyType.DECLARED, DependencyProtocol.HTTP);
        graphViewRepository.refresh();

        List<BlastRadiusRow> rows = graphViewRepository.findBlastRadius(tenantId, a, BlastRadiusDirection.DOWNSTREAM, 6);

        assertThat(rows).extracting(BlastRadiusRow::serviceId, BlastRadiusRow::depth)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(c, 1),
                        org.assertj.core.groups.Tuple.tuple(b, 2));
    }

    @Test
    void findBlastRadiusIsScopedToTenant() {
        UUID tenantId = UUID.randomUUID();
        UUID otherTenant = UUID.randomUUID();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        saveDependency(otherTenant, a, b, DependencyType.DECLARED, DependencyProtocol.HTTP);
        graphViewRepository.refresh();

        List<BlastRadiusRow> rows = graphViewRepository.findBlastRadius(tenantId, a, BlastRadiusDirection.UPSTREAM, 6);

        assertThat(rows).isEmpty();
    }

    @Test
    void diamondFanInDedupesToShortestDistance() {
        UUID tenantId = UUID.randomUUID();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        UUID d = UUID.randomUUID();
        saveDependency(tenantId, a, b, DependencyType.DECLARED, DependencyProtocol.HTTP);
        saveDependency(tenantId, a, c, DependencyType.DECLARED, DependencyProtocol.HTTP);
        saveDependency(tenantId, b, d, DependencyType.DECLARED, DependencyProtocol.HTTP);
        saveDependency(tenantId, c, d, DependencyType.DECLARED, DependencyProtocol.HTTP);
        graphViewRepository.refresh();

        List<BlastRadiusRow> rows = graphViewRepository.findBlastRadius(tenantId, a, BlastRadiusDirection.UPSTREAM, 6);

        assertThat(rows).filteredOn(row -> row.serviceId().equals(d)).hasSize(1)
                .first().satisfies(row -> assertThat(row.depth()).isEqualTo(2));
    }

    @Test
    void findCyclesReturnsEachCycleOnceAnchoredAtItsSmallestMember() {
        UUID tenantId = UUID.randomUUID();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        saveDependency(tenantId, a, b, DependencyType.DECLARED, DependencyProtocol.HTTP);
        saveDependency(tenantId, b, c, DependencyType.DECLARED, DependencyProtocol.HTTP);
        saveDependency(tenantId, c, a, DependencyType.DECLARED, DependencyProtocol.HTTP);
        graphViewRepository.refresh();

        List<List<UUID>> paths = graphViewRepository.findCycles(tenantId, null, 8, 100);

        assertThat(paths).hasSize(1);
        List<UUID> path = paths.getFirst();
        assertThat(path).hasSize(4);
        assertThat(path.get(0)).isEqualTo(path.get(3));
        assertThat(path).containsExactlyInAnyOrder(a, b, c, path.get(0));
    }

    @Test
    void findCyclesOrdersRowsSoTheRowCapIsDeterministic() {
        UUID tenantId = UUID.randomUUID();
        for (int i = 0; i < 4; i++) {
            UUID x = UUID.randomUUID();
            UUID y = UUID.randomUUID();
            saveDependency(tenantId, x, y, DependencyType.DECLARED, DependencyProtocol.HTTP);
            saveDependency(tenantId, y, x, DependencyType.DECLARED, DependencyProtocol.HTTP);
        }
        graphViewRepository.refresh();

        List<List<UUID>> all = graphViewRepository.findCycles(tenantId, null, 8, 100);
        List<List<UUID>> capped = graphViewRepository.findCycles(tenantId, null, 8, 2);

        assertThat(all).hasSize(4);
        assertThat(capped).isEqualTo(all.subList(0, 2));
    }

    @Test
    void findCyclesRespectsTheLengthCap() {
        UUID tenantId = UUID.randomUUID();
        List<UUID> ids = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        for (int i = 0; i < ids.size(); i++) {
            saveDependency(tenantId, ids.get(i), ids.get((i + 1) % ids.size()), DependencyType.DECLARED, DependencyProtocol.HTTP);
        }
        graphViewRepository.refresh();

        List<List<UUID>> paths = graphViewRepository.findCycles(tenantId, null, 3, 100);

        assertThat(paths).isEmpty();
    }

    @Test
    void findCyclesFiltersByType() {
        UUID tenantId = UUID.randomUUID();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        saveDependency(tenantId, a, b, DependencyType.OBSERVED, DependencyProtocol.HTTP);
        saveDependency(tenantId, b, a, DependencyType.OBSERVED, DependencyProtocol.HTTP);
        graphViewRepository.refresh();

        assertThat(graphViewRepository.findCycles(tenantId, DependencyType.DECLARED, 8, 100)).isEmpty();
        assertThat(graphViewRepository.findCycles(tenantId, DependencyType.OBSERVED, 8, 100)).hasSize(1);
    }

    @Test
    void findCyclesDoesNotMultiplyWorkOrRowsOnParallelEdges() {
        UUID tenantId = UUID.randomUUID();
        List<UUID> ids = java.util.stream.Stream.generate(UUID::randomUUID).limit(9).toList();
        for (int i = 0; i < ids.size(); i++) {
            UUID from = ids.get(i);
            UUID to = ids.get((i + 1) % ids.size());
            for (DependencyType type : DependencyType.values()) {
                for (DependencyProtocol protocol : DependencyProtocol.values()) {
                    saveDependency(tenantId, from, to, type, protocol);
                }
            }
        }
        graphViewRepository.refresh();

        long start = System.nanoTime();
        List<List<UUID>> within8 = graphViewRepository.findCycles(tenantId, null, 8, 100);
        List<List<UUID>> within9 = graphViewRepository.findCycles(tenantId, null, 9, 100);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(within8).isEmpty();
        assertThat(within9).hasSize(1);
        assertThat(elapsedMs).isLessThan(5_000);
    }

    @Test
    void findFanInCountsDistinctDependentsEvenWithParallelEdges() {
        UUID tenantId = UUID.randomUUID();
        UUID hub = UUID.randomUUID();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        saveDependency(tenantId, a, hub, DependencyType.DECLARED, DependencyProtocol.HTTP);
        saveDependency(tenantId, a, hub, DependencyType.OBSERVED, DependencyProtocol.GRPC);
        saveDependency(tenantId, b, hub, DependencyType.DECLARED, DependencyProtocol.HTTP);
        graphViewRepository.refresh();

        List<io.cartogra.topology.repository.FanInRow> rows = graphViewRepository.findFanIn(tenantId, 2, 10);

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().serviceId()).isEqualTo(hub);
        assertThat(rows.getFirst().fanIn()).isEqualTo(2);
        assertThat(graphViewRepository.findFanIn(tenantId, 3, 10)).isEmpty();
    }

    @Test
    void findFanInIsScopedToTenant() {
        UUID tenantId = UUID.randomUUID();
        UUID hub = UUID.randomUUID();
        saveDependency(UUID.randomUUID(), UUID.randomUUID(), hub, DependencyType.DECLARED, DependencyProtocol.HTTP);
        graphViewRepository.refresh();

        assertThat(graphViewRepository.findFanIn(tenantId, 1, 10)).isEmpty();
    }
}
