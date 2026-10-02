package io.cartogra.topology.domain;

import io.cartogra.topology.repository.DependencyGraphViewRepository;
import io.cartogra.topology.repository.FanInRow;
import io.cartogra.topology.repository.GraphNodeRepository;
import io.cartogra.topology.repository.TenantTopologySettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.jspecify.annotations.Nullable;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SpofServiceTest {

    @Mock TenantTopologySettingsRepository settingsRepository;
    @Mock DependencyGraphViewRepository graphViewRepository;
    @Mock GraphNodeRepository graphNodeRepository;

    private SpofService service;
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new SpofService(settingsRepository, graphViewRepository, graphNodeRepository);
    }

    private static GraphNode node(UUID id, String name, @Nullable UUID teamId, @Nullable String tier) {
        Instant now = Instant.now();
        return new GraphNode(UUID.randomUUID(), UUID.randomUUID(), id, name, teamId, tier, "HEALTHY", now, now, null);
    }

    @Test
    void noTenantOverrideUsesDefaultThreshold() {
        when(settingsRepository.findSpofThreshold(tenantId)).thenReturn(Optional.empty());
        when(graphViewRepository.findFanIn(eq(tenantId), eq(SpofService.DEFAULT_THRESHOLD), anyInt())).thenReturn(List.of());

        SpofResult result = service.detect(tenantId);

        assertThat(result.threshold()).isEqualTo(SpofService.DEFAULT_THRESHOLD);
    }

    @Test
    void tenantOverrideThresholdIsUsed() {
        when(settingsRepository.findSpofThreshold(tenantId)).thenReturn(Optional.of(10));
        when(graphViewRepository.findFanIn(eq(tenantId), eq(10), anyInt())).thenReturn(List.of());

        SpofResult result = service.detect(tenantId);

        assertThat(result.threshold()).isEqualTo(10);
    }

    @Test
    void rationaleMentionsTheThreshold() {
        when(settingsRepository.findSpofThreshold(tenantId)).thenReturn(Optional.of(7));
        when(graphViewRepository.findFanIn(eq(tenantId), eq(7), anyInt())).thenReturn(List.of());

        SpofResult result = service.detect(tenantId);

        assertThat(result.rationale()).contains("7");
    }

    @Test
    void criticalTierEscalatesSeverity() {
        UUID id = UUID.randomUUID();
        stubDefaultThreshold();
        when(graphViewRepository.findFanIn(any(), anyInt(), anyInt())).thenReturn(List.of(new FanInRow(id, 6)));
        when(graphNodeRepository.findByServiceIds(eq(tenantId), eq(Set.of(id)), anyInt()))
                .thenReturn(List.of(node(id, "svc", UUID.randomUUID(), "CRITICAL")));

        SpofResult result = service.detect(tenantId);

        assertThat(result.items()).singleElement().extracting(Spof::severity).isEqualTo(RiskSeverity.CRITICAL);
    }

    @Test
    void orphanEscalatesSeverityEvenAtStandardTier() {
        UUID id = UUID.randomUUID();
        stubDefaultThreshold();
        when(graphViewRepository.findFanIn(any(), anyInt(), anyInt())).thenReturn(List.of(new FanInRow(id, 6)));
        when(graphNodeRepository.findByServiceIds(eq(tenantId), eq(Set.of(id)), anyInt()))
                .thenReturn(List.of(node(id, "svc", null, "STANDARD")));

        SpofResult result = service.detect(tenantId);

        assertThat(result.items()).singleElement().extracting(Spof::severity).isEqualTo(RiskSeverity.CRITICAL);
    }

    @Test
    void standardTierNonOrphanIsWarning() {
        UUID id = UUID.randomUUID();
        stubDefaultThreshold();
        when(graphViewRepository.findFanIn(any(), anyInt(), anyInt())).thenReturn(List.of(new FanInRow(id, 6)));
        when(graphNodeRepository.findByServiceIds(eq(tenantId), eq(Set.of(id)), anyInt()))
                .thenReturn(List.of(node(id, "svc", UUID.randomUUID(), "STANDARD")));

        SpofResult result = service.detect(tenantId);

        assertThat(result.items()).singleElement().extracting(Spof::severity).isEqualTo(RiskSeverity.WARNING);
    }

    @Test
    void emptyGraphReturnsNoItemsButKeepsThresholdAndRationale() {
        stubDefaultThreshold();
        when(graphViewRepository.findFanIn(any(), anyInt(), anyInt())).thenReturn(List.of());

        SpofResult result = service.detect(tenantId);

        assertThat(result.items()).isEmpty();
        assertThat(result.threshold()).isEqualTo(SpofService.DEFAULT_THRESHOLD);
        assertThat(result.rationale()).isNotBlank();
    }

    @Test
    void nodeMissingFromLiveLookupIsSilentlyDropped() {
        UUID present = UUID.randomUUID();
        UUID deletedBetweenReadAndLookup = UUID.randomUUID();
        stubDefaultThreshold();
        when(graphViewRepository.findFanIn(any(), anyInt(), anyInt()))
                .thenReturn(List.of(new FanInRow(present, 6), new FanInRow(deletedBetweenReadAndLookup, 6)));
        when(graphNodeRepository.findByServiceIds(eq(tenantId), eq(Set.of(present, deletedBetweenReadAndLookup)), anyInt()))
                .thenReturn(List.of(node(present, "present", null, null)));

        SpofResult result = service.detect(tenantId);

        assertThat(result.items()).extracting(Spof::node).extracting(GraphNode::serviceId).containsExactly(present);
    }

    private void stubDefaultThreshold() {
        when(settingsRepository.findSpofThreshold(tenantId)).thenReturn(Optional.empty());
    }

    @Test
    void thresholdBelowOneFromStorageIsClampedToOne() {
        when(settingsRepository.findSpofThreshold(tenantId)).thenReturn(Optional.of(0));
        when(graphViewRepository.findFanIn(eq(tenantId), eq(1), anyInt())).thenReturn(List.of());

        assertThat(service.detect(tenantId).threshold()).isEqualTo(1);
    }

    @Test
    void deletedNodesAreFilteredBeforeTheRowCapSoTheyDoNotConsumeASlot() {
        when(settingsRepository.findSpofThreshold(tenantId)).thenReturn(Optional.empty());
        List<UUID> ids = java.util.stream.Stream.generate(UUID::randomUUID).limit(SpofService.MAX_ROWS + 1).toList();
        List<FanInRow> rows = ids.stream().map(id -> new FanInRow(id, 6)).toList();
        when(graphViewRepository.findFanIn(eq(tenantId), eq(SpofService.DEFAULT_THRESHOLD), eq(SpofService.MAX_ROWS + 1)))
                .thenReturn(rows);
        List<GraphNode> live = ids.stream().skip(1).map(id -> node(id, "svc", UUID.randomUUID(), "STANDARD")).toList();
        when(graphNodeRepository.findByServiceIds(eq(tenantId), any(), anyInt())).thenReturn(live);

        SpofResult result = service.detect(tenantId);

        assertThat(result.items()).hasSize(SpofService.MAX_ROWS);
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void reportsTruncationWhenMoreLiveServicesQualifyThanTheCap() {
        when(settingsRepository.findSpofThreshold(tenantId)).thenReturn(Optional.empty());
        List<UUID> ids = java.util.stream.Stream.generate(UUID::randomUUID).limit(SpofService.MAX_ROWS + 1).toList();
        when(graphViewRepository.findFanIn(eq(tenantId), eq(SpofService.DEFAULT_THRESHOLD), eq(SpofService.MAX_ROWS + 1)))
                .thenReturn(ids.stream().map(id -> new FanInRow(id, 6)).toList());
        when(graphNodeRepository.findByServiceIds(eq(tenantId), any(), anyInt()))
                .thenReturn(ids.stream().map(id -> node(id, "svc", UUID.randomUUID(), "STANDARD")).toList());

        SpofResult result = service.detect(tenantId);

        assertThat(result.items()).hasSize(SpofService.MAX_ROWS);
        assertThat(result.truncated()).isTrue();
    }
}
