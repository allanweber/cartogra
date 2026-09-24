package io.cartogra.topology.domain;

import io.cartogra.topology.repository.DependencyGraphViewRepository;
import io.cartogra.topology.repository.GraphNodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GraphServiceTest {

    @Mock GraphNodeRepository graphNodeRepository;
    @Mock DependencyGraphViewRepository dependencyGraphViewRepository;

    private GraphService service;
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new GraphService(graphNodeRepository, dependencyGraphViewRepository);
    }

    private static GraphNode node(UUID serviceId, String name, UUID teamId) {
        Instant now = Instant.now();
        return new GraphNode(UUID.randomUUID(), UUID.randomUUID(), serviceId, name, teamId, null, "HEALTHY", now, now, null);
    }

    private GraphEdge edge(UUID source, UUID target, DependencyType type) {
        return new GraphEdge(tenantId, source, target, type, DependencyProtocol.HTTP, null);
    }

    @Test
    void emptyTenantReturnsEmptyGraphWithoutQueryingEdges() {
        when(graphNodeRepository.findForGraph(eq(tenantId), any(), anyInt())).thenReturn(List.of());

        Graph graph = service.read(tenantId, null, null, null);

        assertThat(graph.nodes()).isEmpty();
        assertThat(graph.edges()).isEmpty();
        assertThat(graph.truncated()).isFalse();
        verify(dependencyGraphViewRepository, never()).findByServiceIds(any(), any(), any());
    }

    @Test
    void withinCapReturnsAllNodesAndEdgesNotTruncated() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(graphNodeRepository.findForGraph(eq(tenantId), any(), eq(GraphService.MAX_NODES + 1)))
                .thenReturn(List.of(node(a, "svc-a", null), node(b, "svc-b", null)));
        when(dependencyGraphViewRepository.findByServiceIds(eq(tenantId), any(), any()))
                .thenReturn(List.of(edge(a, b, DependencyType.DECLARED)));

        Graph graph = service.read(tenantId, null, null, null);

        assertThat(graph.nodes()).extracting(GraphNode::serviceId).containsExactlyInAnyOrder(a, b);
        assertThat(graph.edges()).hasSize(1);
        assertThat(graph.truncated()).isFalse();
    }

    @Test
    void primaryNodesExceedingLimitAreTruncatedAndEdgesAreStillFetchedForThem() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(graphNodeRepository.findForGraph(eq(tenantId), any(), eq(2)))
                .thenReturn(List.of(node(a, "svc-a", null), node(b, "svc-b", null)));

        Graph graph = service.read(tenantId, null, null, 1);

        assertThat(graph.nodes()).hasSize(1);
        assertThat(graph.nodes().get(0).serviceId()).isEqualTo(a);
        assertThat(graph.truncated()).isTrue();
        verify(dependencyGraphViewRepository).findByServiceIds(eq(tenantId), eq(Set.of(a)), any());
    }

    @Test
    void limitBelowZeroIsRejected() {
        assertThatThrownBy(() -> service.read(tenantId, null, null, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requestedLimitAboveHardCapIsClampedToTheCap() {
        when(graphNodeRepository.findForGraph(eq(tenantId), any(), eq(GraphService.MAX_NODES + 1)))
                .thenReturn(List.of());

        service.read(tenantId, null, null, GraphService.MAX_NODES * 10);

        verify(graphNodeRepository).findForGraph(eq(tenantId), any(), eq(GraphService.MAX_NODES + 1));
    }

    @Test
    void typeFilterIsPassedToEdgeQueryButDoesNotPruneNodes() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(graphNodeRepository.findForGraph(any(), any(), anyInt()))
                .thenReturn(List.of(node(a, "svc-a", null), node(b, "svc-b", null)));
        when(dependencyGraphViewRepository.findByServiceIds(any(), any(), eq(DependencyType.OBSERVED)))
                .thenReturn(List.of());

        Graph graph = service.read(tenantId, null, DependencyType.OBSERVED, null);

        assertThat(graph.nodes()).hasSize(2);
        assertThat(graph.edges()).isEmpty();
        verify(dependencyGraphViewRepository).findByServiceIds(any(), any(), eq(DependencyType.OBSERVED));
    }

    @Test
    void crossTeamNeighborIsIncludedAndEdgeIsNotDropped() {
        UUID teamId = UUID.randomUUID();
        UUID teamMember = UUID.randomUUID();
        UUID otherTeamNeighbor = UUID.randomUUID();
        when(graphNodeRepository.findForGraph(eq(tenantId), eq(teamId), anyInt()))
                .thenReturn(List.of(node(teamMember, "team-svc", teamId)));
        when(dependencyGraphViewRepository.findByServiceIds(eq(tenantId), eq(Set.of(teamMember)), any()))
                .thenReturn(List.of(edge(teamMember, otherTeamNeighbor, DependencyType.DECLARED)));
        when(graphNodeRepository.findByServiceIds(eq(tenantId), eq(Set.of(otherTeamNeighbor)), anyInt()))
                .thenReturn(List.of(node(otherTeamNeighbor, "other-team-svc", UUID.randomUUID())));

        Graph graph = service.read(tenantId, teamId, null, null);

        assertThat(graph.nodes()).extracting(GraphNode::serviceId).containsExactlyInAnyOrder(teamMember, otherTeamNeighbor);
        assertThat(graph.edges()).hasSize(1);
        assertThat(graph.truncated()).isFalse();
    }

    @Test
    void neighborBeyondRemainingBudgetIsDroppedAndMarksTruncated() {
        UUID teamId = UUID.randomUUID();
        UUID teamMember = UUID.randomUUID();
        UUID neighbor = UUID.randomUUID();
        when(graphNodeRepository.findForGraph(eq(tenantId), eq(teamId), eq(2)))
                .thenReturn(List.of(node(teamMember, "team-svc", teamId)));
        when(dependencyGraphViewRepository.findByServiceIds(eq(tenantId), eq(Set.of(teamMember)), any()))
                .thenReturn(List.of(edge(teamMember, neighbor, DependencyType.DECLARED)));

        Graph graph = service.read(tenantId, teamId, null, 1);

        assertThat(graph.nodes()).extracting(GraphNode::serviceId).containsExactly(teamMember);
        assertThat(graph.edges()).isEmpty();
        assertThat(graph.truncated()).isTrue();
        verify(graphNodeRepository, never()).findByServiceIds(any(), any(), anyInt());
    }

    @Test
    void danglingEdgeToAnExcludedNeighborIsFilteredOut() {
        UUID teamId = UUID.randomUUID();
        UUID teamMember = UUID.randomUUID();
        UUID reachableNeighbor = UUID.randomUUID();
        UUID unreachableNeighbor = UUID.randomUUID();
        when(graphNodeRepository.findForGraph(eq(tenantId), eq(teamId), eq(3)))
                .thenReturn(List.of(node(teamMember, "team-svc", teamId)));
        when(dependencyGraphViewRepository.findByServiceIds(eq(tenantId), eq(Set.of(teamMember)), any()))
                .thenReturn(List.of(
                        edge(teamMember, reachableNeighbor, DependencyType.DECLARED),
                        edge(teamMember, unreachableNeighbor, DependencyType.DECLARED)));
        when(graphNodeRepository.findByServiceIds(eq(tenantId), eq(Set.of(reachableNeighbor, unreachableNeighbor)), eq(2)))
                .thenReturn(List.of(node(reachableNeighbor, "a-reachable", null), node(unreachableNeighbor, "b-unreachable", null)));

        Graph graph = service.read(tenantId, teamId, null, 2);

        assertThat(graph.nodes()).extracting(GraphNode::serviceId).containsExactlyInAnyOrder(teamMember, reachableNeighbor);
        assertThat(graph.edges()).hasSize(1);
        assertThat(graph.edges().get(0).targetServiceId()).isEqualTo(reachableNeighbor);
        assertThat(graph.truncated()).isTrue();
    }
}
