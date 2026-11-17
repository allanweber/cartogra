package io.cartogra.topology.domain;

import io.cartogra.common.event.EventEnvelope;
import io.cartogra.topology.domain.event.ServiceLifecyclePayload;
import io.cartogra.topology.infrastructure.scheduled.DependencyGraphViewRefreshScheduler;
import io.cartogra.topology.repository.DependencyRepository;
import io.cartogra.topology.repository.GraphNodeRepository;
import io.cartogra.topology.repository.GraphNodeUpsert;
import io.cartogra.topology.repository.ProcessedEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GraphNodeServiceTest {

    @Mock GraphNodeRepository graphNodeRepository;
    @Mock ProcessedEventRepository processedEventRepository;
    @Mock DependencyRepository dependencyRepository;
    @Mock DependencyGraphViewRefreshScheduler graphViewRefreshScheduler;
    @Mock UnresolvedSpanServiceService unresolvedSpanServiceService;

    private GraphNodeService service;

    @BeforeEach
    void setUp() {
        service = new GraphNodeService(graphNodeRepository, processedEventRepository,
                dependencyRepository, graphViewRefreshScheduler, unresolvedSpanServiceService);
    }

    private static EventEnvelope<ServiceLifecyclePayload> envelope(String eventType, ServiceLifecyclePayload payload) {
        return EventEnvelope.of(eventType, payload.id(), payload.tenantId(), 1, payload);
    }

    @Test
    void registeredEventUpsertsNode() {
        UUID tenantId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();
        var payload = new ServiceLifecyclePayload(serviceId, tenantId, "payments", null, "STANDARD", "HEALTHY", null);
        var env = envelope("service.registered", payload);
        when(processedEventRepository.markProcessed(tenantId, env.eventId())).thenReturn(true);

        service.applyLifecycleEvent(env);

        verify(graphNodeRepository).upsert(new GraphNodeUpsert(tenantId, serviceId, "payments", null, "STANDARD", "HEALTHY"));
        verify(unresolvedSpanServiceService).markRegistered(tenantId, "payments");
        verify(graphNodeRepository, never()).softDelete(any(), any(), any());
        verify(dependencyRepository, never()).softDeleteAllForService(any(), any());
        verify(graphViewRefreshScheduler, never()).markDirty();
    }

    @Test
    void deletedEventSoftDeletesNodeUsingPayloadTimestamp() {
        UUID tenantId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();
        Instant deletedAt = Instant.parse("2026-08-01T00:00:00Z");
        var payload = new ServiceLifecyclePayload(serviceId, tenantId, "payments", null, "STANDARD", "HEALTHY", deletedAt);
        var env = envelope("service.deleted", payload);
        when(processedEventRepository.markProcessed(tenantId, env.eventId())).thenReturn(true);

        service.applyLifecycleEvent(env);

        verify(graphNodeRepository).softDelete(tenantId, serviceId, deletedAt);
        verify(graphNodeRepository, never()).upsert(any());
        verify(unresolvedSpanServiceService, never()).markRegistered(any(), any());
        verify(dependencyRepository).softDeleteAllForService(tenantId, serviceId);
        verify(graphViewRefreshScheduler).markDirty();
    }

    @Test
    void replayedEnvelopeIsANoOp() {
        UUID tenantId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();
        var payload = new ServiceLifecyclePayload(serviceId, tenantId, "payments", null, "STANDARD", "HEALTHY", null);
        var env = envelope("service.registered", payload);
        when(processedEventRepository.markProcessed(tenantId, env.eventId())).thenReturn(false);

        service.applyLifecycleEvent(env);

        verify(graphNodeRepository, never()).upsert(any());
        verify(graphNodeRepository, never()).softDelete(any(), any(), any());
        verify(dependencyRepository, never()).softDeleteAllForService(any(), any());
        verify(graphViewRefreshScheduler, never()).markDirty();
    }
}
