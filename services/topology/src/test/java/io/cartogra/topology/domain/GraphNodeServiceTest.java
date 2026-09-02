package io.cartogra.topology.domain;

import io.cartogra.common.event.EventEnvelope;
import io.cartogra.topology.domain.event.ServiceLifecyclePayload;
import io.cartogra.topology.infrastructure.registry.RegistryGraphNodeClient;
import io.cartogra.topology.infrastructure.registry.RegistryServiceSnapshot;
import io.cartogra.topology.repository.GraphNodeRepository;
import io.cartogra.topology.repository.GraphNodeUpsert;
import io.cartogra.topology.repository.ProcessedEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GraphNodeServiceTest {

    @Mock GraphNodeRepository graphNodeRepository;
    @Mock ProcessedEventRepository processedEventRepository;
    @Mock RegistryGraphNodeClient registryClient;

    private GraphNodeService service;

    @BeforeEach
    void setUp() {
        service = new GraphNodeService(graphNodeRepository, processedEventRepository, registryClient);
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
        verify(graphNodeRepository, never()).softDelete(any(), any(), any());
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
    }

    @Test
    void backfillPagesThroughRegistryUntilAShortPage() {
        UUID tenantId = UUID.randomUUID();
        List<RegistryServiceSnapshot> fullPage = java.util.stream.IntStream.range(0, 200)
                .mapToObj(i -> new RegistryServiceSnapshot(UUID.randomUUID(), tenantId, "svc-" + i, null, null, "HEALTHY"))
                .toList();
        List<RegistryServiceSnapshot> shortPage = List.of(
                new RegistryServiceSnapshot(UUID.randomUUID(), tenantId, "svc-last", null, null, "HEALTHY"));
        when(registryClient.listActiveServices(anyInt(), anyInt()))
                .thenReturn(fullPage)
                .thenReturn(shortPage);

        int upserted = service.backfill();

        assertThat(upserted).isEqualTo(201);
        verify(registryClient, times(2)).listActiveServices(anyInt(), anyInt());
        verify(graphNodeRepository, times(201)).upsert(any());
    }
}
