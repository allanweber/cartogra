package io.cartogra.topology.domain;

import io.cartogra.topology.domain.exception.UnknownServiceNodeException;
import io.cartogra.topology.repository.BlastRadiusRow;
import io.cartogra.topology.repository.DependencyGraphViewRepository;
import io.cartogra.topology.repository.GraphNodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
class BlastRadiusServiceTest {

    @Mock GraphNodeRepository graphNodeRepository;
    @Mock DependencyGraphViewRepository dependencyGraphViewRepository;

    private BlastRadiusService service;
    private final UUID tenantId = UUID.randomUUID();
    private final UUID serviceId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new BlastRadiusService(graphNodeRepository, dependencyGraphViewRepository);
    }

    private static GraphNode node(UUID id, String name) {
        Instant now = Instant.now();
        return new GraphNode(UUID.randomUUID(), UUID.randomUUID(), id, name, null, null, "HEALTHY", now, now, null);
    }

    private void stubLiveNode() {
        when(graphNodeRepository.findByServiceId(tenantId, serviceId)).thenReturn(Optional.of(node(serviceId, "svc")));
    }

    @Test
    void unknownNodeThrows() {
        when(graphNodeRepository.findByServiceId(tenantId, serviceId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.compute(tenantId, serviceId, null, null))
                .isInstanceOf(UnknownServiceNodeException.class);
    }

    @Test
    void softDeletedNodeThrows() {
        Instant now = Instant.now();
        GraphNode deleted = new GraphNode(UUID.randomUUID(), tenantId, serviceId, "svc", null, null, "HEALTHY", now, now, now);
        when(graphNodeRepository.findByServiceId(tenantId, serviceId)).thenReturn(Optional.of(deleted));

        assertThatThrownBy(() -> service.compute(tenantId, serviceId, null, null))
                .isInstanceOf(UnknownServiceNodeException.class);
    }

    @Test
    void nullDepthUsesDefault() {
        stubLiveNode();
        when(dependencyGraphViewRepository.findBlastRadius(any(), any(), any(), anyInt())).thenReturn(List.of());

        BlastRadius result = service.compute(tenantId, serviceId, null, null);

        assertThat(result.maxDepth()).isEqualTo(BlastRadiusService.DEFAULT_DEPTH);
    }

    @Test
    void depthAboveMaxIsClamped() {
        stubLiveNode();
        when(dependencyGraphViewRepository.findBlastRadius(any(), any(), any(), anyInt())).thenReturn(List.of());

        BlastRadius result = service.compute(tenantId, serviceId, null, BlastRadiusService.MAX_DEPTH * 10);

        assertThat(result.maxDepth()).isEqualTo(BlastRadiusService.MAX_DEPTH);
    }

    @Test
    void depthBelowOneIsRejected() {
        stubLiveNode();

        assertThatThrownBy(() -> service.compute(tenantId, serviceId, null, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void directionFilterSkipsTheOtherDirectionEntirely() {
        stubLiveNode();
        when(dependencyGraphViewRepository.findBlastRadius(eq(tenantId), eq(serviceId), eq(BlastRadiusDirection.UPSTREAM), anyInt()))
                .thenReturn(List.of());

        BlastRadius result = service.compute(tenantId, serviceId, BlastRadiusDirection.UPSTREAM, null);

        assertThat(result.downstream()).isEqualTo(BlastRadiusDirectionResult.empty());
        verify(dependencyGraphViewRepository, never())
                .findBlastRadius(any(), any(), eq(BlastRadiusDirection.DOWNSTREAM), anyInt());
    }

    @Test
    void nodeCapTruncationBeyondConstantIsFlaggedWithExactOverflow() {
        stubLiveNode();
        List<BlastRadiusRow> rows = new java.util.ArrayList<>();
        List<UUID> ids = new java.util.ArrayList<>();
        for (int i = 0; i < BlastRadiusService.MAX_NODES_PER_DIRECTION + 5; i++) {
            UUID id = UUID.randomUUID();
            ids.add(id);
            rows.add(new BlastRadiusRow(id, 1));
        }
        when(dependencyGraphViewRepository.findBlastRadius(eq(tenantId), eq(serviceId), eq(BlastRadiusDirection.UPSTREAM), anyInt()))
                .thenReturn(rows);
        when(dependencyGraphViewRepository.findBlastRadius(eq(tenantId), eq(serviceId), eq(BlastRadiusDirection.DOWNSTREAM), anyInt()))
                .thenReturn(List.of());
        List<GraphNode> cappedNodes = ids.subList(0, BlastRadiusService.MAX_NODES_PER_DIRECTION).stream()
                .map(id -> node(id, "svc-" + id)).toList();
        when(graphNodeRepository.findByServiceIds(eq(tenantId), any(), anyInt())).thenReturn(cappedNodes);

        BlastRadius result = service.compute(tenantId, serviceId, null, null);

        assertThat(result.upstream().nodeCapTruncated()).isTrue();
        assertThat(result.upstream().nodesBeyondCap()).isEqualTo(5);
        assertThat(result.upstream().depthTruncated()).isFalse();
        assertThat(result.upstream().entries()).hasSize(BlastRadiusService.MAX_NODES_PER_DIRECTION);
    }

    @Test
    void depthCapTruncationExcludesPeekRowAndCountsIt() {
        stubLiveNode();
        UUID within = UUID.randomUUID();
        UUID beyond = UUID.randomUUID();
        int depth = 2;
        when(dependencyGraphViewRepository.findBlastRadius(eq(tenantId), eq(serviceId), eq(BlastRadiusDirection.UPSTREAM), eq(depth)))
                .thenReturn(List.of(new BlastRadiusRow(within, depth), new BlastRadiusRow(beyond, depth + 1)));
        when(dependencyGraphViewRepository.findBlastRadius(eq(tenantId), eq(serviceId), eq(BlastRadiusDirection.DOWNSTREAM), eq(depth)))
                .thenReturn(List.of());
        when(graphNodeRepository.findByServiceIds(eq(tenantId), eq(Set.of(within)), anyInt()))
                .thenReturn(List.of(node(within, "within")));

        BlastRadius result = service.compute(tenantId, serviceId, null, depth);

        assertThat(result.upstream().entries()).extracting(BlastRadiusEntry::node).extracting(GraphNode::serviceId)
                .containsExactly(within);
        assertThat(result.upstream().depthTruncated()).isTrue();
        assertThat(result.upstream().nodesBeyondDepth()).isEqualTo(1);
        assertThat(result.upstream().nodeCapTruncated()).isFalse();
    }

    @Test
    void nodeMissingFromLiveLookupIsSilentlyDropped() {
        stubLiveNode();
        UUID present = UUID.randomUUID();
        UUID deletedBetweenReadAndLookup = UUID.randomUUID();
        when(dependencyGraphViewRepository.findBlastRadius(eq(tenantId), eq(serviceId), eq(BlastRadiusDirection.UPSTREAM), anyInt()))
                .thenReturn(List.of(new BlastRadiusRow(present, 1), new BlastRadiusRow(deletedBetweenReadAndLookup, 1)));
        when(dependencyGraphViewRepository.findBlastRadius(eq(tenantId), eq(serviceId), eq(BlastRadiusDirection.DOWNSTREAM), anyInt()))
                .thenReturn(List.of());
        when(graphNodeRepository.findByServiceIds(eq(tenantId), eq(Set.of(present, deletedBetweenReadAndLookup)), anyInt()))
                .thenReturn(List.of(node(present, "present")));

        BlastRadius result = service.compute(tenantId, serviceId, null, null);

        assertThat(result.upstream().entries()).extracting(BlastRadiusEntry::node).extracting(GraphNode::serviceId)
                .containsExactly(present);
    }
}
