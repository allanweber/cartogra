package io.cartogra.topology.domain;

import io.cartogra.topology.config.DriftProperties;
import io.cartogra.topology.repository.DependencyDriftRepository;
import io.cartogra.topology.repository.DependencyRepository;
import io.cartogra.topology.repository.EdgePairStat;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DriftDetectionServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");
    private static final Duration WINDOW = Duration.ofDays(7);

    @Mock DependencyRepository dependencyRepository;
    @Mock DependencyDriftRepository driftRepository;

    private DriftDetectionService service;

    @BeforeEach
    void setUp() {
        service = new DriftDetectionService(dependencyRepository, driftRepository,
                new DriftProperties(Duration.ofMinutes(5), WINDOW), new SimpleMeterRegistry());
    }

    private void declared(EdgePairStat... pairs) {
        when(dependencyRepository.findLivePairs(TENANT, DependencyType.DECLARED)).thenReturn(List.of(pairs));
    }

    private void observed(EdgePairStat... pairs) {
        when(dependencyRepository.findLivePairs(TENANT, DependencyType.OBSERVED)).thenReturn(List.of(pairs));
    }

    private static EdgePairStat pair(UUID s, UUID t, Instant created, Instant active) {
        return new EdgePairStat(s, t, created, active);
    }

    private ArgumentCaptor<DependencyDrift> inserted(int times) {
        ArgumentCaptor<DependencyDrift> captor = ArgumentCaptor.forClass(DependencyDrift.class);
        verify(driftRepository, org.mockito.Mockito.times(times)).insertIfAbsent(captor.capture());
        return captor;
    }

    private DependencyDrift activeDrift(DriftType type) {
        return new DependencyDrift(UUID.randomUUID(), TENANT, A, B, type, NOW.minusSeconds(60), null, null, null);
    }

    @Test
    void observedPairWithoutDeclaredRaisesUndeclared() {
        declared();
        observed(pair(A, B, NOW.minusSeconds(10), NOW));
        when(driftRepository.findAllActive(TENANT)).thenReturn(List.of());
        when(driftRepository.findDismissed(TENANT)).thenReturn(List.of());
        when(driftRepository.insertIfAbsent(any())).thenReturn(true);

        service.sweep(TENANT, NOW);

        DependencyDrift drift = inserted(1).getValue();
        assertThat(drift.type()).isEqualTo(DriftType.UNDECLARED);
        assertThat(drift.sourceServiceId()).isEqualTo(A);
        assertThat(drift.targetServiceId()).isEqualTo(B);
    }

    @Test
    void declaredPairObservedRecentlyRaisesNothing() {
        declared(pair(A, B, NOW.minus(Duration.ofDays(30)), NOW.minus(Duration.ofDays(30))));
        observed(pair(A, B, NOW.minus(Duration.ofDays(30)), NOW.minus(Duration.ofHours(1))));
        when(driftRepository.findAllActive(TENANT)).thenReturn(List.of());
        when(driftRepository.findDismissed(TENANT)).thenReturn(List.of());

        service.sweep(TENANT, NOW);

        verify(driftRepository, never()).insertIfAbsent(any());
    }

    @Test
    void oldDeclaredPairNeverObservedRaisesMissingWhenTenantHasObservedEdges() {
        UUID other = UUID.randomUUID();
        declared(pair(A, B, NOW.minus(Duration.ofDays(30)), NOW.minus(Duration.ofDays(30))));
        observed(pair(A, other, NOW.minusSeconds(5), NOW));
        declared(pair(A, B, NOW.minus(Duration.ofDays(30)), NOW.minus(Duration.ofDays(30))),
                pair(A, other, NOW.minus(Duration.ofDays(30)), NOW.minus(Duration.ofDays(30))));
        when(driftRepository.findAllActive(TENANT)).thenReturn(List.of());
        when(driftRepository.findDismissed(TENANT)).thenReturn(List.of());
        when(driftRepository.insertIfAbsent(any())).thenReturn(true);

        service.sweep(TENANT, NOW);

        DependencyDrift drift = inserted(1).getValue();
        assertThat(drift.type()).isEqualTo(DriftType.MISSING);
        assertThat(drift.targetServiceId()).isEqualTo(B);
    }

    @Test
    void noMissingDriftWhenTenantHasNoObservedEdges() {
        declared(pair(A, B, NOW.minus(Duration.ofDays(30)), NOW.minus(Duration.ofDays(30))));
        observed();
        when(driftRepository.findAllActive(TENANT)).thenReturn(List.of());
        when(driftRepository.findDismissed(TENANT)).thenReturn(List.of());

        service.sweep(TENANT, NOW);

        verify(driftRepository, never()).insertIfAbsent(any());
    }

    @Test
    void freshlyDeclaredPairIsNotMissingYet() {
        UUID other = UUID.randomUUID();
        declared(pair(A, B, NOW.minus(Duration.ofDays(1)), NOW.minus(Duration.ofDays(1))),
                pair(A, other, NOW.minus(Duration.ofDays(30)), NOW.minus(Duration.ofDays(30))));
        observed(pair(A, other, NOW.minus(Duration.ofDays(30)), NOW));
        when(driftRepository.findAllActive(TENANT)).thenReturn(List.of());
        when(driftRepository.findDismissed(TENANT)).thenReturn(List.of());

        service.sweep(TENANT, NOW);

        verify(driftRepository, never()).insertIfAbsent(any());
    }

    @Test
    void staleObservedEdgeMakesDeclaredPairMissing() {
        declared(pair(A, B, NOW.minus(Duration.ofDays(30)), NOW.minus(Duration.ofDays(30))));
        observed(pair(A, B, NOW.minus(Duration.ofDays(30)), NOW.minus(Duration.ofDays(8))));
        when(driftRepository.findAllActive(TENANT)).thenReturn(List.of());
        when(driftRepository.findDismissed(TENANT)).thenReturn(List.of());
        when(driftRepository.insertIfAbsent(any())).thenReturn(true);

        service.sweep(TENANT, NOW);

        assertThat(inserted(1).getValue().type()).isEqualTo(DriftType.MISSING);
    }

    @Test
    void protocolIsIgnoredWhenComparingPairs() {
        declared(pair(A, B, NOW.minus(Duration.ofDays(30)), NOW.minus(Duration.ofDays(30))));
        observed(pair(A, B, NOW.minus(Duration.ofDays(30)), NOW));
        when(driftRepository.findAllActive(TENANT)).thenReturn(List.of());
        when(driftRepository.findDismissed(TENANT)).thenReturn(List.of());

        service.sweep(TENANT, NOW);

        verify(driftRepository, never()).insertIfAbsent(any());
    }

    @Test
    void existingActiveDriftIsNotInsertedAgain() {
        declared();
        observed(pair(A, B, NOW.minusSeconds(10), NOW));
        when(driftRepository.findAllActive(TENANT)).thenReturn(List.of(activeDrift(DriftType.UNDECLARED)));
        when(driftRepository.findDismissed(TENANT)).thenReturn(List.of());

        service.sweep(TENANT, NOW);

        verify(driftRepository, never()).insertIfAbsent(any());
        verify(driftRepository, never()).resolve(any(), any(), any(), any(), any());
    }

    @Test
    void dismissedDriftIsNotRaisedAgain() {
        declared();
        observed(pair(A, B, NOW.minusSeconds(10), NOW));
        DependencyDrift dismissed = new DependencyDrift(UUID.randomUUID(), TENANT, A, B, DriftType.UNDECLARED,
                NOW.minusSeconds(600), NOW.minusSeconds(300), UUID.randomUUID(), DriftResolution.DISMISSED);
        when(driftRepository.findAllActive(TENANT)).thenReturn(List.of());
        when(driftRepository.findDismissed(TENANT)).thenReturn(List.of(dismissed));

        service.sweep(TENANT, NOW);

        verify(driftRepository, never()).insertIfAbsent(any());
    }

    @Test
    void activeDriftWhoseConditionClearedIsAutoClosed() {
        declared(pair(A, B, NOW.minusSeconds(60), NOW.minusSeconds(60)));
        observed(pair(A, B, NOW.minusSeconds(60), NOW));
        DependencyDrift stale = activeDrift(DriftType.UNDECLARED);
        when(driftRepository.findAllActive(TENANT)).thenReturn(List.of(stale));
        when(driftRepository.findDismissed(TENANT)).thenReturn(List.of());
        when(driftRepository.resolve(TENANT, stale.id(), null, DriftResolution.AUTO, NOW)).thenReturn(true);

        service.sweep(TENANT, NOW);

        verify(driftRepository).resolve(eq(TENANT), eq(stale.id()), isNull(), eq(DriftResolution.AUTO), eq(NOW));
        verify(driftRepository, never()).insertIfAbsent(any());
    }
}
