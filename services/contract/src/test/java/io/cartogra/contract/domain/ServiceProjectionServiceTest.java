package io.cartogra.contract.domain;

import io.cartogra.contract.infrastructure.registry.RegistryNameClient;
import io.cartogra.contract.repository.ServiceProjectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ServiceProjectionServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID SERVICE = UUID.randomUUID();

    @Mock ServiceProjectionRepository repository;
    @Mock RegistryNameClient registry;

    private ServiceProjectionService service;

    @BeforeEach
    void setUp() {
        service = new ServiceProjectionService(repository, registry);
    }

    @Test
    void knownNameIsReadLocally() {
        when(repository.findName(TENANT, SERVICE)).thenReturn(Optional.of("ledger"));

        assertThat(service.nameOf(TENANT, SERVICE)).contains("ledger");
        verifyNoInteractions(registry);
    }

    @Test
    void missingNameIsFetchedAndStored() {
        when(repository.findName(TENANT, SERVICE)).thenReturn(Optional.empty());
        when(registry.findNames(TENANT, List.of(SERVICE))).thenReturn(Map.of(SERVICE, "ledger"));

        assertThat(service.nameOf(TENANT, SERVICE)).contains("ledger");
        verify(repository).upsertNames(eq(TENANT), eq(Map.of(SERVICE, "ledger")), any());
    }

    @Test
    void registryFailureLeavesTheNameEmpty() {
        when(repository.findName(TENANT, SERVICE)).thenReturn(Optional.empty());
        when(registry.findNames(TENANT, List.of(SERVICE))).thenThrow(new RestClientException("down"));

        assertThat(service.nameOf(TENANT, SERVICE)).isEmpty();
        verify(repository, never()).upsertNames(any(), anyMap(), any());
    }

    @Test
    void fillAsksRegistryOnlyWhenSomethingIsUnnamed() {
        when(repository.findUnnamed(TENANT, ServiceProjectionService.MAX_LOOKUP)).thenReturn(List.of());

        service.fillMissingNames(TENANT);

        verifyNoInteractions(registry);
    }

    @Test
    void fillStoresWhatRegistryKnowsInOneBatch() {
        UUID unknown = UUID.randomUUID();
        when(repository.findUnnamed(TENANT, ServiceProjectionService.MAX_LOOKUP)).thenReturn(List.of(SERVICE, unknown));
        when(registry.findNames(TENANT, List.of(SERVICE, unknown))).thenReturn(Map.of(SERVICE, "ledger"));

        service.fillMissingNames(TENANT);

        verify(repository).upsertNames(eq(TENANT), eq(Map.of(SERVICE, "ledger")), any());
    }

    @Test
    void requireExistsAcceptsANameKnownLocallyWithoutCallingRegistry() {
        when(repository.findName(TENANT, SERVICE)).thenReturn(Optional.of("ledger"));

        service.requireExists(TENANT, SERVICE);

        verifyNoInteractions(registry);
    }

    @Test
    void requireExistsAsksRegistryAndStoresTheName() {
        when(repository.findName(TENANT, SERVICE)).thenReturn(Optional.empty());
        when(registry.findNames(TENANT, List.of(SERVICE))).thenReturn(Map.of(SERVICE, "ledger"));

        service.requireExists(TENANT, SERVICE);

        verify(repository).upsertNames(eq(TENANT), eq(Map.of(SERVICE, "ledger")), any());
    }

    @Test
    void requireExistsRejectsAServiceRegistryDoesNotKnowInTheTenant() {
        when(repository.findName(TENANT, SERVICE)).thenReturn(Optional.empty());
        when(registry.findNames(TENANT, List.of(SERVICE))).thenReturn(Map.of());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.requireExists(TENANT, SERVICE))
                .isInstanceOf(io.cartogra.contract.domain.exception.ServiceNotFoundException.class);
        verify(repository, never()).upsertNames(any(), anyMap(), any());
    }

    @Test
    void requireExistsFailsClosedWhenRegistryIsDown() {
        when(repository.findName(TENANT, SERVICE)).thenReturn(Optional.empty());
        when(registry.findNames(TENANT, List.of(SERVICE))).thenThrow(new RestClientException("down"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.requireExists(TENANT, SERVICE))
                .isInstanceOf(io.cartogra.contract.domain.exception.RegistryUnavailableException.class);
    }
}
