package io.cartogra.topology.domain;

import io.cartogra.topology.config.SpanLinkProperties;
import io.cartogra.topology.domain.exception.UnattributedSpanBatchException;
import io.cartogra.topology.domain.exception.UnattributedSpanBatchException.QuarantineReason;
import io.cartogra.topology.infrastructure.scheduled.DependencyGraphViewRefreshScheduler;
import io.cartogra.topology.repository.DependencyRepository;
import io.cartogra.topology.repository.GraphNodeRepository;
import io.cartogra.topology.repository.PendingSpanLink;
import io.cartogra.topology.repository.PendingSpanLinkRepository;
import io.cartogra.topology.repository.UnresolvedSpanServiceRepository;
import io.cartogra.topology.repository.UpsertOutcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ObservedDependencyServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID CHECKOUT = UUID.randomUUID();
    private static final UUID PAYMENTS = UUID.randomUUID();

    @Mock GraphNodeRepository graphNodeRepository;
    @Mock DependencyRepository dependencyRepository;
    @Mock PendingSpanLinkRepository pendingSpanLinkRepository;
    @Mock UnresolvedSpanServiceRepository unresolvedSpanServiceRepository;
    @Mock DependencyGraphViewRefreshScheduler refreshScheduler;

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private ObservedDependencyService service;

    @BeforeEach
    void setUp() {
        service = new ObservedDependencyService(graphNodeRepository, dependencyRepository,
                pendingSpanLinkRepository, unresolvedSpanServiceRepository, refreshScheduler,
                new SpanLinkProperties(Duration.ofMinutes(5), Duration.ofMinutes(1), 1000), meters);
    }

    private void knownNodes() {
        when(graphNodeRepository.findLiveByLowerNames(eq(TENANT), anyCollection())).thenAnswer(inv -> {
            Collection<String> asked = inv.getArgument(1);
            Map<String, UUID> all = Map.of("checkout", CHECKOUT, "payments", PAYMENTS);
            return all.entrySet().stream().filter(e -> asked.contains(e.getKey()))
                    .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        });
    }

    private static SpanObservation client(String service, String spanId, String peerName, String peerHost) {
        return new SpanObservation(service, SpanKind.CLIENT, "trace", spanId, null, peerName, peerHost,
                DependencyProtocol.HTTP);
    }

    private static SpanObservation server(String service, String spanId, String parent) {
        return new SpanObservation(service, SpanKind.SERVER, "trace", spanId, parent, null, null,
                DependencyProtocol.HTTP);
    }

    private double counter(String name, String... tags) {
        return meters.counter(name, tags).count();
    }

    @Test
    void peerServiceResolvesEdgeAndMarksDirtyOnce() {
        knownNodes();
        when(dependencyRepository.upsertObserved(any(), any(), any(), any(), any())).thenReturn(UpsertOutcome.INSERTED);

        service.ingest(TENANT, List.of(
                client("checkout", "c1", "payments", null),
                client("checkout", "c2", "payments", null)));

        verify(dependencyRepository).upsertObserved(eq(TENANT), eq(CHECKOUT), eq(PAYMENTS),
                eq(DependencyProtocol.HTTP), any(Instant.class));
        verify(refreshScheduler).markDirty();
        assertThat(counter("cartogra.spans.edges", "result", "inserted")).isEqualTo(1);
    }

    @Test
    void unchangedUpsertDoesNotMarkDirty() {
        knownNodes();
        when(dependencyRepository.upsertObserved(any(), any(), any(), any(), any())).thenReturn(UpsertOutcome.UNCHANGED);

        service.ingest(TENANT, List.of(client("checkout", "c1", "payments", null)));

        verify(refreshScheduler, never()).markDirty();
    }

    @Test
    void hostThatMatchesACatalogNameResolvesWithoutRecordingUnknown() {
        knownNodes();
        when(dependencyRepository.upsertObserved(any(), any(), any(), any(), any())).thenReturn(UpsertOutcome.REFRESHED);

        service.ingest(TENANT, List.of(client("checkout", "c1", null, "payments")));

        verify(dependencyRepository).upsertObserved(eq(TENANT), eq(CHECKOUT), eq(PAYMENTS), any(), any());
        verify(unresolvedSpanServiceRepository, never()).record(any(), anyString(), any(), anyInt());
    }

    @Test
    void unresolvedPeerServiceIsRecordedOnceAndParkedForFallback() {
        knownNodes();

        service.ingest(TENANT, List.of(
                client("checkout", "c1", "ghost", null),
                client("checkout", "c2", "ghost", null)));

        verify(unresolvedSpanServiceRepository).record(eq(TENANT), eq("ghost"), any(), eq(1000));
        verify(pendingSpanLinkRepository, times(2)).insert(any());
        verify(refreshScheduler, never()).markDirty();
    }

    @Test
    void ipTargetIsNeverRecordedAsUnknown() {
        knownNodes();

        service.ingest(TENANT, List.of(client("checkout", "c1", null, "10.1.2.3")));

        verify(unresolvedSpanServiceRepository, never()).record(any(), anyString(), any(), anyInt());
        verify(pendingSpanLinkRepository).insert(any());
    }

    @Test
    void unresolvedSourceIsRecordedAndSkipped() {
        knownNodes();

        service.ingest(TENANT, List.of(client("stranger", "c1", "payments", null)));

        verify(unresolvedSpanServiceRepository).record(eq(TENANT), eq("stranger"), any(), eq(1000));
        verify(dependencyRepository, never()).upsertObserved(any(), any(), any(), any(), any());
        assertThat(counter("cartogra.spans.skipped", "reason", "unresolved_source")).isEqualTo(1);
    }

    @Test
    void selfCallCreatesNoEdge() {
        knownNodes();

        service.ingest(TENANT, List.of(client("checkout", "c1", "checkout", null)));

        verify(dependencyRepository, never()).upsertObserved(any(), any(), any(), any(), any());
        assertThat(counter("cartogra.spans.skipped", "reason", "self")).isEqualTo(1);
    }

    @Test
    void spanWithoutProtocolIsSkipped() {
        knownNodes();
        var span = new SpanObservation("checkout", SpanKind.CLIENT, "t", "c1", null, "payments", null, null);

        service.ingest(TENANT, List.of(span));

        verify(dependencyRepository, never()).upsertObserved(any(), any(), any(), any(), any());
        assertThat(counter("cartogra.spans.skipped", "reason", "unsupported_protocol")).isEqualTo(1);
    }

    @Test
    void clientAndServerInSameBatchJoinWithoutTouchingTheTable() {
        knownNodes();
        when(dependencyRepository.upsertObserved(any(), any(), any(), any(), any())).thenReturn(UpsertOutcome.INSERTED);
        when(pendingSpanLinkRepository.findServerPartner(any(), any(), any())).thenReturn(Optional.empty());

        service.ingest(TENANT, List.of(
                client("checkout", "c1", null, "10.1.2.3"),
                server("payments", "s1", "c1")));

        verify(dependencyRepository).upsertObserved(eq(TENANT), eq(CHECKOUT), eq(PAYMENTS), any(), any());
        verify(pendingSpanLinkRepository, never()).insert(any());
        assertThat(counter("cartogra.spans.fallback_matched")).isEqualTo(1);
    }

    @Test
    void serverAfterStoredClientJoinsAndDeletesPendingRow() {
        knownNodes();
        UUID rowId = UUID.randomUUID();
        when(pendingSpanLinkRepository.findClientPartner(TENANT, "trace", "c1")).thenReturn(Optional.of(
                new PendingSpanLink(rowId, TENANT, "trace", "c1", null, SpanKind.CLIENT, "checkout", "http",
                        Instant.now().plusSeconds(60))));
        when(dependencyRepository.upsertObserved(any(), any(), any(), any(), any())).thenReturn(UpsertOutcome.INSERTED);

        service.ingest(TENANT, List.of(server("payments", "s1", "c1")));

        verify(pendingSpanLinkRepository).delete(rowId);
        verify(dependencyRepository).upsertObserved(eq(TENANT), eq(CHECKOUT), eq(PAYMENTS),
                eq(DependencyProtocol.HTTP), any());
    }

    @Test
    void clientAfterStoredServerJoinsAndDeletesPendingRow() {
        knownNodes();
        UUID rowId = UUID.randomUUID();
        when(pendingSpanLinkRepository.findServerPartner(TENANT, "trace", "c1")).thenReturn(Optional.of(
                new PendingSpanLink(rowId, TENANT, "trace", "s1", "c1", SpanKind.SERVER, "payments", "http",
                        Instant.now().plusSeconds(60))));
        when(dependencyRepository.upsertObserved(any(), any(), any(), any(), any())).thenReturn(UpsertOutcome.INSERTED);

        service.ingest(TENANT, List.of(client("checkout", "c1", null, "10.1.2.3")));

        verify(pendingSpanLinkRepository).delete(rowId);
        verify(dependencyRepository).upsertObserved(eq(TENANT), eq(CHECKOUT), eq(PAYMENTS), any(), any());
    }

    @Test
    void rootServerSpanIsIgnored() {
        knownNodes();

        service.ingest(TENANT, List.of(server("payments", "s1", null)));

        verify(pendingSpanLinkRepository, never()).insert(any());
        verify(dependencyRepository, never()).upsertObserved(any(), any(), any(), any(), any());
    }

    @Test
    void unmatchedServerIsParkedWithTtl() {
        knownNodes();
        when(pendingSpanLinkRepository.findClientPartner(any(), any(), any())).thenReturn(Optional.empty());

        service.ingest(TENANT, List.of(server("payments", "s1", "c1")));

        var captor = ArgumentCaptor.forClass(PendingSpanLink.class);
        verify(pendingSpanLinkRepository).insert(captor.capture());
        assertThat(captor.getValue().kind()).isEqualTo(SpanKind.SERVER);
        assertThat(captor.getValue().expiresAt()).isAfter(Instant.now().plus(Duration.ofMinutes(4)));
    }

    @Test
    void tenantValidation() {
        assertThatThrownBy(() -> service.requireAttributedTenant(null))
                .isInstanceOfSatisfying(UnattributedSpanBatchException.class,
                        e -> assertThat(e.reason()).isEqualTo(QuarantineReason.TENANT_MISSING));
        assertThatThrownBy(() -> service.requireAttributedTenant("  "))
                .isInstanceOf(UnattributedSpanBatchException.class);
        assertThatThrownBy(() -> service.requireAttributedTenant("nope"))
                .isInstanceOfSatisfying(UnattributedSpanBatchException.class,
                        e -> assertThat(e.reason()).isEqualTo(QuarantineReason.TENANT_MALFORMED));
        when(graphNodeRepository.existsLiveForTenant(TENANT)).thenReturn(false);
        assertThatThrownBy(() -> service.requireAttributedTenant(TENANT.toString()))
                .isInstanceOfSatisfying(UnattributedSpanBatchException.class,
                        e -> assertThat(e.reason()).isEqualTo(QuarantineReason.TENANT_UNKNOWN));
        assertThat(counter("cartogra.spans.quarantined", "reason", "tenant_missing")).isEqualTo(2);
    }

    @Test
    void knownTenantPasses() {
        when(graphNodeRepository.existsLiveForTenant(TENANT)).thenReturn(true);

        assertThat(service.requireAttributedTenant(TENANT.toString())).isEqualTo(TENANT);
    }

    @Test
    void fallbackLockIsTakenOncePerBatchAndOnlyWhenNeeded() {
        knownNodes();
        when(pendingSpanLinkRepository.findServerPartner(any(), any(), any())).thenReturn(Optional.empty());
        when(pendingSpanLinkRepository.findClientPartner(any(), any(), any())).thenReturn(Optional.empty());

        service.ingest(TENANT, List.of(
                client("checkout", "c1", null, "10.1.2.3"),
                client("checkout", "c2", null, "10.1.2.4"),
                server("payments", "s1", "c9")));

        verify(pendingSpanLinkRepository, times(1)).lockTenant(TENANT);
    }

    @Test
    void directlyResolvedBatchNeverLocksOrLooksUpPartners() {
        knownNodes();
        when(dependencyRepository.upsertObserved(any(), any(), any(), any(), any())).thenReturn(UpsertOutcome.INSERTED);

        service.ingest(TENANT, List.of(client("checkout", "c1", "payments", null)));

        verify(pendingSpanLinkRepository, never()).lockTenant(any());
    }

    @Test
    void serverSpanOfDirectlyResolvedClientInSameBatchIsNotParked() {
        knownNodes();
        when(dependencyRepository.upsertObserved(any(), any(), any(), any(), any())).thenReturn(UpsertOutcome.INSERTED);

        service.ingest(TENANT, List.of(
                client("checkout", "c1", "payments", null),
                server("payments", "s1", "c1")));

        verify(pendingSpanLinkRepository, never()).findClientPartner(any(), any(), any());
        verify(pendingSpanLinkRepository, never()).insert(any());
    }

    @Test
    void peerNameResolvedByFallbackJoinIsNotRecordedAsUnknown() {
        knownNodes();
        when(dependencyRepository.upsertObserved(any(), any(), any(), any(), any())).thenReturn(UpsertOutcome.INSERTED);
        UUID rowId = UUID.randomUUID();
        when(pendingSpanLinkRepository.findServerPartner(TENANT, "trace", "c1")).thenReturn(Optional.of(
                new PendingSpanLink(rowId, TENANT, "trace", "s1", "c1", SpanKind.SERVER, "payments", "http",
                        Instant.now().plusSeconds(60))));

        service.ingest(TENANT, List.of(client("checkout", "c1", "ghost-alias", null)));

        verify(dependencyRepository).upsertObserved(eq(TENANT), eq(CHECKOUT), eq(PAYMENTS), any(), any());
        verify(unresolvedSpanServiceRepository, never()).record(any(), anyString(), any(), anyInt());
    }
}
