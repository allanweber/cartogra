package io.cartogra.contract.domain;

import io.cartogra.contract.domain.exception.ContractNotFoundException;
import io.cartogra.contract.repository.ContractRepository;
import io.cartogra.contract.repository.InboundEdgeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConsumerServiceTest {

    private static final UUID TENANT = UUID.randomUUID();

    @Mock InboundEdgeRepository edgeRepository;
    @Mock ContractRepository contractRepository;
    @Mock ServiceProjectionService serviceProjectionService;

    private ConsumerService service() {
        return new ConsumerService(edgeRepository, contractRepository, serviceProjectionService);
    }

    private final InboundEdge edge = new InboundEdge(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            "declared", "http");

    @Test
    void declaredAndObservedUpsertAndRemovedMarksRemoved() {
        ConsumerService service = service();

        assertThat(service.apply(TENANT, "dependency.declared", edge)).isTrue();
        assertThat(service.apply(TENANT, "dependency.observed", edge)).isTrue();
        assertThat(service.apply(TENANT, "dependency.removed", edge)).isTrue();

        verify(edgeRepository, times(2)).upsert(eq(TENANT), eq(edge), any(Instant.class));
        verify(edgeRepository).markRemoved(eq(TENANT), eq(edge), any(Instant.class));
    }

    @Test
    void anUnknownEventTypeChangesNothing() {
        assertThat(service().apply(TENANT, "dependency.renamed", edge)).isFalse();
        verifyNoInteractions(edgeRepository);
    }

    @Test
    void unnamedConsumersGetTheirNamesInOneLookup() {
        UUID contractId = UUID.randomUUID();
        UUID producer = UUID.randomUUID();
        UUID named = UUID.randomUUID();
        UUID unnamedA = UUID.randomUUID();
        UUID unnamedB = UUID.randomUUID();
        when(contractRepository.findLive(TENANT, contractId)).thenReturn(Optional.of(contract(contractId, producer)));
        when(edgeRepository.findConsumers(eq(TENANT), eq(producer), eq(ConsumerService.API_PROTOCOLS), eq(50), eq(0)))
                .thenReturn(List.of(consumer(named, "alpha"), consumer(unnamedA, null), consumer(unnamedB, null)));
        when(edgeRepository.countConsumers(TENANT, producer, ConsumerService.API_PROTOCOLS)).thenReturn(3L);
        when(serviceProjectionService.fetchNames(TENANT, List.of(unnamedA, unnamedB)))
                .thenReturn(Map.of(unnamedA, "beta"));

        var page = service().consumersOf(TENANT, contractId, 50, 0);

        assertThat(page.total()).isEqualTo(3);
        assertThat(page.items()).extracting(Consumer::serviceName).containsExactly("alpha", "beta", null);
    }

    @Test
    void namedConsumersNeedNoLookupAndTheLimitIsClamped() {
        UUID contractId = UUID.randomUUID();
        UUID producer = UUID.randomUUID();
        when(contractRepository.findLive(TENANT, contractId)).thenReturn(Optional.of(contract(contractId, producer)));
        when(edgeRepository.findConsumers(eq(TENANT), eq(producer), any(), eq(100), eq(0)))
                .thenReturn(List.of(consumer(UUID.randomUUID(), "alpha")));

        var page = service().consumersOf(TENANT, contractId, 1000, -5);

        assertThat(page.limit()).isEqualTo(100);
        assertThat(page.offset()).isZero();
        verify(serviceProjectionService, never()).fetchNames(any(), any());
    }

    @Test
    void consumersAreBounded() {
        UUID producer = UUID.randomUUID();
        when(edgeRepository.findConsumers(eq(TENANT), eq(producer), any(), eq(ConsumerService.MAX_CHECK_CONSUMERS),
                eq(0))).thenReturn(List.of(consumer(UUID.randomUUID(), "alpha")));

        assertThat(service().allConsumersOfService(TENANT, producer)).hasSize(1);

        assertThat(ConsumerService.MAX_CHECK_CONSUMERS).isEqualTo(500);
    }

    @Test
    void aContractThatIsNotLiveIs404() {
        UUID contractId = UUID.randomUUID();
        when(contractRepository.findLive(TENANT, contractId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().consumersOf(TENANT, contractId, 50, 0))
                .isInstanceOf(ContractNotFoundException.class);
        verify(edgeRepository, never()).findConsumers(any(), any(), any(), anyInt(), anyInt());
    }

    private static ApiContract contract(UUID id, UUID serviceId) {
        Instant now = Instant.now();
        return new ApiContract(id, TENANT, serviceId, ApiContract.OPENAPI, now, now, null);
    }

    private static Consumer consumer(UUID id, String name) {
        return new Consumer(id, name, List.of("declared"), List.of("http"));
    }
}
