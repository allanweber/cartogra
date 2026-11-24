package io.cartogra.contract.domain;

import io.cartogra.common.identity.Actor;
import io.cartogra.contract.domain.exception.BaselineNotFoundException;
import io.cartogra.contract.domain.exception.CheckServiceNotFoundException;
import io.cartogra.contract.domain.exception.InvalidSpecException;
import io.cartogra.contract.domain.exception.ServiceAmbiguousException;
import io.cartogra.contract.repository.ContractCheckRepository;
import io.cartogra.contract.repository.ContractRepository;
import io.cartogra.contract.repository.ContractVersionRepository;
import io.cartogra.contract.repository.ServiceProjectionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContractCheckServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID SERVICE = UUID.randomUUID();
    private static final UUID KEY = UUID.randomUUID();
    private static final Actor ACTOR = Actor.apiKey(KEY);

    private static final String BASELINE = """
            openapi: 3.0.3
            info: {title: T, version: 1.0.0}
            paths:
              /a:
                get:
                  responses:
                    '200': {description: ok}
              /b:
                get:
                  responses:
                    '200': {description: ok}
            """;
    private static final String BREAKING = """
            openapi: 3.0.3
            info: {title: T, version: 2.0.0}
            paths:
              /a:
                get:
                  responses:
                    '200': {description: ok}
            """;
    private static final String COMPATIBLE = BASELINE + """
              /c:
                get:
                  responses:
                    '200': {description: ok}
            """;

    @Mock ServiceProjectionRepository serviceRepository;
    @Mock ContractRepository contractRepository;
    @Mock ContractVersionRepository versionRepository;
    @Mock ContractCheckRepository checkRepository;
    @Mock ConsumerService consumerService;

    private final SpecParser parser = new SpecParser();
    private final BreakingChangeEngine engine = new BreakingChangeEngine();

    private ContractCheckService service() {
        return new ContractCheckService(serviceRepository, contractRepository, versionRepository, checkRepository,
                consumerService, parser, engine);
    }

    private void givenBaseline() {
        UUID contractId = UUID.randomUUID();
        Instant now = Instant.now();
        when(contractRepository.findLiveByService(TENANT, SERVICE)).thenReturn(Optional.of(
                new ApiContract(contractId, TENANT, SERVICE, ApiContract.OPENAPI, now, now, null)));
        ParsedSpec parsed = parser.parse(BASELINE);
        when(versionRepository.findLatest(TENANT, contractId)).thenReturn(Optional.of(new ContractVersion(
                UUID.randomUUID(), TENANT, contractId, 2, null, BASELINE, parsed.canonical(), parsed.hash(), false,
                now, ContractVersion.SOURCE_MANUAL, null, null, null)));
    }

    @Test
    void breakingByNameListsConsumersAndStoresOneRow() {
        when(serviceRepository.findLiveIdsByName(TENANT, "LEDGER", 2)).thenReturn(List.of(SERVICE));
        givenBaseline();
        Consumer consumer = new Consumer(UUID.randomUUID(), "web", List.of("declared"), List.of("http"));
        when(consumerService.allConsumersOfService(TENANT, SERVICE)).thenReturn(List.of(consumer));

        CheckResult result = service().check(TENANT, ACTOR, "LEDGER", BREAKING);

        assertThat(result.isBreaking()).isTrue();
        assertThat(result.baselineVersion()).isEqualTo(2);
        assertThat(result.affectedConsumers()).containsExactly(consumer);
        ArgumentCaptor<ContractCheck> row = ArgumentCaptor.forClass(ContractCheck.class);
        verify(checkRepository).insert(row.capture());
        assertThat(row.getValue().apiKeyId()).isEqualTo(KEY);
        assertThat(row.getValue().breaking()).isTrue();
        assertThat(row.getValue().affectedConsumerIds()).containsExactly(consumer.serviceId());
    }

    @Test
    void compatibleByIdHasNoConsumersAndDoesNotLoadThem() {
        when(serviceRepository.findLiveId(TENANT, SERVICE)).thenReturn(Optional.of(SERVICE));
        givenBaseline();

        CheckResult result = service().check(TENANT, ACTOR, SERVICE.toString(), COMPATIBLE);

        assertThat(result.isBreaking()).isFalse();
        assertThat(result.affectedConsumers()).isEmpty();
        verifyNoInteractions(consumerService);
        verify(checkRepository).insert(any());
    }

    @Test
    void unknownServiceIsNotFound() {
        when(serviceRepository.findLiveIdsByName(TENANT, "nope", 2)).thenReturn(List.of());
        when(serviceRepository.findLiveId(TENANT, SERVICE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().check(TENANT, ACTOR, "nope", BASELINE))
                .isInstanceOf(CheckServiceNotFoundException.class);
        assertThatThrownBy(() -> service().check(TENANT, ACTOR, SERVICE.toString(), BASELINE))
                .isInstanceOf(CheckServiceNotFoundException.class);
        verify(checkRepository, never()).insert(any());
    }

    @Test
    void twoNameMatchesAreAmbiguous() {
        when(serviceRepository.findLiveIdsByName(TENANT, "billing", 2))
                .thenReturn(List.of(SERVICE, UUID.randomUUID()));

        assertThatThrownBy(() -> service().check(TENANT, ACTOR, "billing", BASELINE))
                .isInstanceOf(ServiceAmbiguousException.class);
        verify(checkRepository, never()).insert(any());
    }

    @Test
    void noContractMeansNoBaseline() {
        when(serviceRepository.findLiveId(TENANT, SERVICE)).thenReturn(Optional.of(SERVICE));
        when(contractRepository.findLiveByService(TENANT, SERVICE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().check(TENANT, ACTOR, SERVICE.toString(), BASELINE))
                .isInstanceOf(BaselineNotFoundException.class);
        verify(checkRepository, never()).insert(any());
    }

    @Test
    void invalidSpecStoresNothing() {
        when(serviceRepository.findLiveId(TENANT, SERVICE)).thenReturn(Optional.of(SERVICE));
        givenBaseline();

        assertThatThrownBy(() -> service().check(TENANT, ACTOR, SERVICE.toString(), "swagger: '2.0'"))
                .isInstanceOf(InvalidSpecException.class);
        verify(checkRepository, never()).insert(any());
    }

    @Test
    void anActorWithoutApiKeyIsDenied() {
        assertThatThrownBy(() -> service().check(TENANT, Actor.admin(UUID.randomUUID()), "x", BASELINE))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(serviceRepository, checkRepository);
    }
}
