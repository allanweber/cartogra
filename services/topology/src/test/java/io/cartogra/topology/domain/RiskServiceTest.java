package io.cartogra.topology.domain;

import io.cartogra.topology.repository.DependencyDriftRepository;
import io.cartogra.topology.repository.GraphNodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.jspecify.annotations.Nullable;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RiskServiceTest {

    @Mock CycleService cycleService;
    @Mock SpofService spofService;
    @Mock DependencyDriftRepository driftRepository;
    @Mock GraphNodeRepository graphNodeRepository;

    private RiskService service;
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new RiskService(cycleService, spofService, driftRepository, graphNodeRepository);
        when(cycleService.find(any(), any())).thenReturn(new Cycles(List.of(), false));
        when(spofService.detect(any())).thenReturn(new SpofResult(5, "rationale", List.of()));
        when(graphNodeRepository.findOrphaned(any(), anyInt())).thenReturn(List.of());
        when(driftRepository.findActive(any(), anyInt(), anyInt())).thenReturn(List.of());
    }

    private static GraphNode node(UUID id, String name, @Nullable UUID teamId, @Nullable String tier) {
        Instant now = Instant.now();
        return new GraphNode(UUID.randomUUID(), UUID.randomUUID(), id, name, teamId, tier, "HEALTHY", now, now, null);
    }

    @Test
    void emptyEverythingReturnsEmptyPage() {
        RiskPage result = service.list(tenantId, 20, 0);

        assertThat(result.items()).isEmpty();
        assertThat(result.total()).isZero();
    }

    @Test
    void combinesAllFourSources() {
        UUID cycleMember1 = UUID.randomUUID();
        UUID cycleMember2 = UUID.randomUUID();
        when(cycleService.find(any(), any())).thenReturn(new Cycles(List.of(new Cycle(List.of(cycleMember1, cycleMember2))), false));

        UUID spofId = UUID.randomUUID();
        when(spofService.detect(any())).thenReturn(new SpofResult(5, "rationale",
                List.of(new Spof(node(spofId, "spof-svc", UUID.randomUUID(), "STANDARD"), 6, RiskSeverity.WARNING))));

        UUID orphanId = UUID.randomUUID();
        when(graphNodeRepository.findOrphaned(any(), anyInt())).thenReturn(List.of(node(orphanId, "orphan-svc", null, "STANDARD")));

        UUID source = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        DependencyDrift drift = new DependencyDrift(UUID.randomUUID(), tenantId, source, target, DriftType.UNDECLARED,
                Instant.now(), null, null);
        when(driftRepository.findActive(any(), anyInt(), anyInt())).thenReturn(List.of(drift));

        RiskPage result = service.list(tenantId, 20, 0);

        assertThat(result.total()).isEqualTo(4);
        assertThat(result.items()).extracting(Risk::type)
                .containsExactlyInAnyOrder(RiskType.CYCLE, RiskType.SPOF, RiskType.ORPHAN, RiskType.DRIFT);
    }

    @Test
    void sortsBySeverityThenTypeThenTitle() {
        UUID spofId = UUID.randomUUID();
        when(spofService.detect(any())).thenReturn(new SpofResult(5, "rationale",
                List.of(new Spof(node(spofId, "b-svc", UUID.randomUUID(), "STANDARD"), 6, RiskSeverity.WARNING))));

        UUID orphanId = UUID.randomUUID();
        when(graphNodeRepository.findOrphaned(any(), anyInt()))
                .thenReturn(List.of(node(orphanId, "a-svc", null, "CRITICAL")));

        RiskPage result = service.list(tenantId, 20, 0);

        assertThat(result.items()).hasSize(2);
        assertThat(result.items().get(0).severity()).isEqualTo(RiskSeverity.CRITICAL);
        assertThat(result.items().get(1).severity()).isEqualTo(RiskSeverity.WARNING);
    }

    @Test
    void paginationSlicesCorrectlyAndTotalReflectsFullCount() {
        List<GraphNode> orphans = List.of(
                node(UUID.randomUUID(), "svc-1", null, "STANDARD"),
                node(UUID.randomUUID(), "svc-2", null, "STANDARD"),
                node(UUID.randomUUID(), "svc-3", null, "STANDARD"));
        when(graphNodeRepository.findOrphaned(any(), anyInt())).thenReturn(orphans);

        RiskPage firstPage = service.list(tenantId, 2, 0);
        RiskPage secondPage = service.list(tenantId, 2, 2);

        assertThat(firstPage.total()).isEqualTo(3);
        assertThat(firstPage.items()).hasSize(2);
        assertThat(secondPage.items()).hasSize(1);
    }

    @Test
    void cycleSeverityIsCriticalWhenAnyMemberIsCriticalTier() {
        UUID critical = UUID.randomUUID();
        UUID standard = UUID.randomUUID();
        when(cycleService.find(any(), any())).thenReturn(new Cycles(List.of(new Cycle(List.of(critical, standard))), false));
        when(graphNodeRepository.findByServiceIds(any(), any(), anyInt())).thenReturn(List.of(
                node(critical, "critical-svc", UUID.randomUUID(), "CRITICAL"),
                node(standard, "standard-svc", UUID.randomUUID(), "STANDARD")));

        RiskPage result = service.list(tenantId, 20, 0);

        assertThat(result.items()).singleElement().extracting(Risk::severity).isEqualTo(RiskSeverity.CRITICAL);
    }

    @Test
    void cycleSeverityIsInfoWhenAllMembersAreExperimentalTier() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(cycleService.find(any(), any())).thenReturn(new Cycles(List.of(new Cycle(List.of(a, b))), false));
        when(graphNodeRepository.findByServiceIds(any(), any(), anyInt())).thenReturn(List.of(
                node(a, "a-svc", UUID.randomUUID(), "EXPERIMENTAL"),
                node(b, "b-svc", UUID.randomUUID(), "EXPERIMENTAL")));

        RiskPage result = service.list(tenantId, 20, 0);

        assertThat(result.items()).singleElement().extracting(Risk::severity).isEqualTo(RiskSeverity.INFO);
    }

    @Test
    void orphanSeverityDowngradesForExperimentalTier() {
        UUID id = UUID.randomUUID();
        when(graphNodeRepository.findOrphaned(any(), anyInt())).thenReturn(List.of(node(id, "svc", null, "EXPERIMENTAL")));

        RiskPage result = service.list(tenantId, 20, 0);

        assertThat(result.items()).singleElement().extracting(Risk::severity).isEqualTo(RiskSeverity.INFO);
    }

    @Test
    void driftUndeclaredIsWarningAndMissingIsInfo() {
        UUID s1 = UUID.randomUUID();
        UUID t1 = UUID.randomUUID();
        UUID s2 = UUID.randomUUID();
        UUID t2 = UUID.randomUUID();
        DependencyDrift undeclared = new DependencyDrift(UUID.randomUUID(), tenantId, s1, t1, DriftType.UNDECLARED,
                Instant.now(), null, null);
        DependencyDrift missing = new DependencyDrift(UUID.randomUUID(), tenantId, s2, t2, DriftType.MISSING,
                Instant.now(), null, null);
        when(driftRepository.findActive(any(), anyInt(), anyInt())).thenReturn(List.of(undeclared, missing));

        RiskPage result = service.list(tenantId, 20, 0);

        assertThat(result.items()).filteredOn(r -> r.id().equals("drift:" + undeclared.id()))
                .singleElement().extracting(Risk::severity).isEqualTo(RiskSeverity.WARNING);
        assertThat(result.items()).filteredOn(r -> r.id().equals("drift:" + missing.id()))
                .singleElement().extracting(Risk::severity).isEqualTo(RiskSeverity.INFO);
    }

    @Test
    void idsAreStableAcrossRepeatedCalls() {
        UUID orphanId = UUID.randomUUID();
        when(graphNodeRepository.findOrphaned(any(), anyInt())).thenReturn(List.of(node(orphanId, "svc", null, "STANDARD")));

        RiskPage first = service.list(tenantId, 20, 0);
        RiskPage second = service.list(tenantId, 20, 0);

        assertThat(first.items().get(0).id()).isEqualTo(second.items().get(0).id());
    }

    @Test
    void notTruncatedWhenNoSourceHitsItsCap() {
        assertThat(service.list(tenantId, 20, 0).truncated()).isFalse();
    }

    @Test
    void truncatedWhenCycleSourceWasCapped() {
        when(cycleService.find(any(), any())).thenReturn(new Cycles(List.of(), true));

        assertThat(service.list(tenantId, 20, 0).truncated()).isTrue();
    }

    @Test
    void truncatedWhenSpofSourceWasCapped() {
        when(spofService.detect(any())).thenReturn(new SpofResult(5, "rationale", List.of(), true));

        assertThat(service.list(tenantId, 20, 0).truncated()).isTrue();
    }

    @Test
    void orphanSourceOverTheCapIsTrimmedAndFlaggedTruncated() {
        List<GraphNode> orphans = java.util.stream.IntStream.rangeClosed(0, SpofService.MAX_ROWS)
                .mapToObj(i -> node(UUID.randomUUID(), "svc-" + i, null, "STANDARD"))
                .toList();
        when(graphNodeRepository.findOrphaned(any(), anyInt())).thenReturn(orphans);

        RiskPage result = service.list(tenantId, 500, 0);

        assertThat(result.total()).isEqualTo(SpofService.MAX_ROWS);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void equalSeverityTypeAndTitleOrderByIdSoPagingIsStable() {
        UUID m1 = UUID.randomUUID();
        UUID m2 = UUID.randomUUID();
        UUID m3 = UUID.randomUUID();
        UUID m4 = UUID.randomUUID();
        Cycle c1 = new Cycle(List.of(m1, m2));
        Cycle c2 = new Cycle(List.of(m3, m4));
        when(cycleService.find(any(), any())).thenReturn(new Cycles(List.of(c1, c2), false));
        RiskPage forward = service.list(tenantId, 20, 0);
        when(cycleService.find(any(), any())).thenReturn(new Cycles(List.of(c2, c1), false));
        RiskPage reversed = service.list(tenantId, 20, 0);

        assertThat(reversed.items()).extracting(Risk::id).containsExactlyElementsOf(
                forward.items().stream().map(Risk::id).toList());
    }
}
