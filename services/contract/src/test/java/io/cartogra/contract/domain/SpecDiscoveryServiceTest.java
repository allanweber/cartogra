package io.cartogra.contract.domain;

import io.cartogra.common.identity.Actor;
import io.cartogra.contract.domain.exception.ContractAlreadyExistsException;
import io.cartogra.contract.domain.exception.ServiceDeletedException;
import io.cartogra.contract.repository.ContractRepository;
import io.cartogra.contract.repository.DiscoveredSpecRepository;
import io.cartogra.contract.repository.ServiceProjectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SpecDiscoveryServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID SERVICE = UUID.randomUUID();
    private static final String REPO = "acme/payments";

    @Mock DiscoveredSpecRepository specRepository;
    @Mock ContractRepository contractRepository;
    @Mock ServiceProjectionRepository projectionRepository;
    @Mock ServiceProjectionService projectionService;
    @Mock ContractService contractService;
    @Mock PlatformTransactionManager transactionManager;

    private SpecDiscoveryService service;

    @BeforeEach
    void setUp() {
        service = new SpecDiscoveryService(specRepository, contractRepository, projectionRepository,
                projectionService, contractService, new SpecParser(), transactionManager);
        lenient().when(specRepository.lockPending(any(), any()))
                .thenAnswer(inv -> Optional.of(spec(DiscoveredSpecStatus.PENDING, SpecParserTest.YAML_30)));
    }

    @Test
    void aSpecInAFinalStateOfALiveServiceChangesNothing() {
        when(specRepository.recordLatest(any()))
                .thenReturn(spec(DiscoveredSpecStatus.APPLIED, SpecParserTest.YAML_30, SERVICE));
        when(projectionRepository.isDeleted(TENANT, SERVICE)).thenReturn(false);

        service.ingest(payload(SpecParserTest.YAML_30));

        verifyNoInteractions(projectionService, contractService);
        verify(specRepository, never()).reopenLatest(any());
        verify(specRepository, never()).markStatus(any(), any(), any(), any(), any(), anyList());
    }

    @Test
    void anAppliedSpecOfADeletedServiceIsReopenedAndAppliedToTheLiveServiceOfTheRepo() {
        UUID deleted = UUID.randomUUID();
        DiscoveredSpec applied = spec(DiscoveredSpecStatus.APPLIED, SpecParserTest.YAML_30, deleted);
        DiscoveredSpec reopened = spec(DiscoveredSpecStatus.PENDING, SpecParserTest.YAML_30);
        when(specRepository.recordLatest(any())).thenReturn(applied);
        when(projectionRepository.isDeleted(TENANT, deleted)).thenReturn(true);
        when(specRepository.reopenLatest(applied)).thenReturn(reopened);
        when(projectionService.findLiveByExternalId(TENANT, REPO)).thenReturn(Optional.of(SERVICE));
        when(contractRepository.findLiveByService(TENANT, SERVICE)).thenReturn(Optional.empty());
        when(contractService.createInTx(eq(TENANT), eq(Actor.SYSTEM), eq(SERVICE), any(), any(),
                eq(ContractVersion.SOURCE_DISCOVERY), eq(reopened.id()))).thenReturn(published(1));

        service.ingest(payload(SpecParserTest.YAML_30));

        verify(specRepository).markStatus(eq(TENANT), eq(reopened.id()), eq(DiscoveredSpecStatus.APPLIED),
                eq(SERVICE), any(), eq(List.of()));
    }

    @Test
    void aSkippedSpecIsReopenedAndStaysPendingWhenNoServiceIsLive() {
        DiscoveredSpec skipped = spec(DiscoveredSpecStatus.SKIPPED, SpecParserTest.YAML_30, UUID.randomUUID());
        when(specRepository.recordLatest(any())).thenReturn(skipped);
        when(specRepository.reopenLatest(skipped)).thenReturn(spec(DiscoveredSpecStatus.PENDING, SpecParserTest.YAML_30));
        when(projectionService.findLiveByExternalId(TENANT, REPO)).thenReturn(Optional.empty());
        when(projectionService.findInRegistryByExternalId(TENANT, REPO)).thenReturn(Optional.empty());

        service.ingest(payload(SpecParserTest.YAML_30));

        verify(specRepository).reopenLatest(skipped);
        verifyNoInteractions(contractService);
        verify(specRepository, never()).markStatus(any(), any(), any(), any(), any(), anyList());
    }

    @Test
    void aRejectedSpecIsNotReopened() {
        when(specRepository.recordLatest(any()))
                .thenReturn(spec(DiscoveredSpecStatus.REJECTED, "swagger: '2.0'\n", UUID.randomUUID()));

        service.ingest(payload(SpecParserTest.YAML_30));

        verify(specRepository, never()).reopenLatest(any());
        verifyNoInteractions(projectionService, contractService);
    }

    @Test
    void noServiceLeavesTheSpecPending() {
        when(specRepository.recordLatest(any())).thenReturn(spec(DiscoveredSpecStatus.PENDING, SpecParserTest.YAML_30));
        when(projectionService.findLiveByExternalId(TENANT, REPO)).thenReturn(Optional.empty());
        when(projectionService.findInRegistryByExternalId(TENANT, REPO)).thenReturn(Optional.empty());

        service.ingest(payload(SpecParserTest.YAML_30));

        verifyNoInteractions(contractService);
        verify(specRepository, never()).markStatus(any(), any(), any(), any(), any(), anyList());
    }

    @Test
    void aRegistryHitIsStoredInTheProjectionAndApplied() {
        when(specRepository.recordLatest(any())).thenReturn(spec(DiscoveredSpecStatus.PENDING, SpecParserTest.YAML_30));
        when(projectionService.findLiveByExternalId(TENANT, REPO)).thenReturn(Optional.empty());
        when(projectionService.findInRegistryByExternalId(TENANT, REPO)).thenReturn(Optional.of(SERVICE));
        when(contractRepository.findLiveByService(TENANT, SERVICE)).thenReturn(Optional.empty());
        when(contractService.createInTx(eq(TENANT), eq(Actor.SYSTEM), eq(SERVICE), any(), any(),
                eq(ContractVersion.SOURCE_DISCOVERY), any())).thenReturn(published(1));

        service.ingest(payload(SpecParserTest.YAML_30));

        verify(projectionService).recordExternalId(TENANT, SERVICE, REPO);
        verify(specRepository).markStatus(eq(TENANT), any(), eq(DiscoveredSpecStatus.APPLIED), eq(SERVICE), any(),
                eq(List.of()));
    }

    @Test
    void noContractCreatesTheContractAsTheSystemActor() {
        when(contractRepository.findLiveByService(TENANT, SERVICE)).thenReturn(Optional.empty());
        DiscoveredSpec spec = spec(DiscoveredSpecStatus.PENDING, SpecParserTest.YAML_30);
        when(contractService.createInTx(eq(TENANT), eq(Actor.SYSTEM), eq(SERVICE), eq(spec.specRaw()), any(),
                eq(ContractVersion.SOURCE_DISCOVERY), eq(spec.id()))).thenReturn(published(1));

        assertThat(service.apply(spec, SERVICE)).isEqualTo(DiscoveredSpecStatus.APPLIED);
    }

    @Test
    void aChangedSpecPublishesTheNextVersion() {
        UUID contractId = UUID.randomUUID();
        when(contractRepository.findLiveByService(TENANT, SERVICE)).thenReturn(Optional.of(contract(contractId)));
        when(contractService.publishInTx(eq(TENANT), eq(Actor.SYSTEM), eq(contractId), any(), any(),
                eq(ContractVersion.SOURCE_DISCOVERY), any())).thenReturn(new VersionPublication(version(2), true));

        assertThat(service.apply(spec(DiscoveredSpecStatus.PENDING, SpecParserTest.YAML_30), SERVICE))
                .isEqualTo(DiscoveredSpecStatus.APPLIED);
    }

    @Test
    void theSameCanonicalHashGivesUnchanged() {
        UUID contractId = UUID.randomUUID();
        when(contractRepository.findLiveByService(TENANT, SERVICE)).thenReturn(Optional.of(contract(contractId)));
        when(contractService.publishInTx(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new VersionPublication(version(1), false));

        assertThat(service.apply(spec(DiscoveredSpecStatus.PENDING, SpecParserTest.YAML_30), SERVICE))
                .isEqualTo(DiscoveredSpecStatus.UNCHANGED);
        verify(specRepository).markStatus(eq(TENANT), any(), eq(DiscoveredSpecStatus.UNCHANGED), eq(SERVICE), any(),
                eq(List.of()));
    }

    @Test
    void anInvalidSpecIsRejectedWithTheParserErrors() {
        assertThat(service.apply(spec(DiscoveredSpecStatus.PENDING, "swagger: '2.0'\n"), SERVICE))
                .isEqualTo(DiscoveredSpecStatus.REJECTED);
        verify(specRepository).markStatus(eq(TENANT), any(), eq(DiscoveredSpecStatus.REJECTED), eq(SERVICE), isNull(),
                argThat(errors -> !errors.isEmpty()));
        verifyNoInteractions(contractService);
    }

    @Test
    void aTombstonedServiceIsSkipped() {
        when(projectionRepository.isDeleted(TENANT, SERVICE)).thenReturn(true);

        assertThat(service.apply(spec(DiscoveredSpecStatus.PENDING, SpecParserTest.YAML_30), SERVICE))
                .isEqualTo(DiscoveredSpecStatus.SKIPPED);
        verifyNoInteractions(contractService);
    }

    @Test
    void aRaceWithAManualCreatePublishesAVersionInstead() {
        UUID contractId = UUID.randomUUID();
        when(contractRepository.findLiveByService(TENANT, SERVICE))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(contract(contractId)));
        when(contractService.createInTx(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new DuplicateKeyException("api_contracts_live_service_uq"));
        when(contractService.publishInTx(eq(TENANT), eq(Actor.SYSTEM), eq(contractId), any(), any(), any(), any()))
                .thenReturn(new VersionPublication(version(2), true));

        assertThat(service.apply(spec(DiscoveredSpecStatus.PENDING, SpecParserTest.YAML_30), SERVICE))
                .isEqualTo(DiscoveredSpecStatus.APPLIED);
    }

    @Test
    void aContractFoundInsideTheTransactionIsAlsoARace() {
        UUID contractId = UUID.randomUUID();
        when(contractRepository.findLiveByService(TENANT, SERVICE))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(contract(contractId)));
        when(contractService.createInTx(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new ContractAlreadyExistsException(SERVICE, contractId));
        when(contractService.publishInTx(any(), any(), eq(contractId), any(), any(), any(), any()))
                .thenReturn(new VersionPublication(version(2), true));

        assertThat(service.apply(spec(DiscoveredSpecStatus.PENDING, SpecParserTest.YAML_30), SERVICE))
                .isEqualTo(DiscoveredSpecStatus.APPLIED);
    }

    @Test
    void aServiceDeletedDuringTheRaceRetryGivesSkipped() {
        UUID contractId = UUID.randomUUID();
        when(contractRepository.findLiveByService(TENANT, SERVICE))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(contract(contractId)));
        when(contractService.createInTx(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new DuplicateKeyException("api_contracts_live_service_uq"));
        when(contractService.publishInTx(any(), any(), eq(contractId), any(), any(), any(), any()))
                .thenThrow(new ServiceDeletedException(SERVICE));

        assertThat(service.apply(spec(DiscoveredSpecStatus.PENDING, SpecParserTest.YAML_30), SERVICE))
                .isEqualTo(DiscoveredSpecStatus.SKIPPED);
        verify(specRepository).markStatus(eq(TENANT), any(), eq(DiscoveredSpecStatus.SKIPPED), eq(SERVICE), isNull(),
                eq(List.of()));
    }

    @Test
    void aSpecThatIsNoLongerPendingIsNotAppliedTwice() {
        when(specRepository.lockPending(any(), any())).thenReturn(Optional.empty());

        assertThat(service.apply(spec(DiscoveredSpecStatus.PENDING, SpecParserTest.YAML_30), SERVICE))
                .isEqualTo(DiscoveredSpecStatus.PENDING);
        verifyNoInteractions(contractService);
    }

    @Test
    void applyPendingAppliesEachPendingSpecAndAFailureDoesNotStopTheOthers() {
        DiscoveredSpec bad = spec(DiscoveredSpecStatus.PENDING, SpecParserTest.YAML_30);
        DiscoveredSpec good = spec(DiscoveredSpecStatus.PENDING, SpecParserTest.YAML_30);
        when(specRepository.findPending(TENANT, REPO)).thenReturn(List.of(bad, good));
        when(contractRepository.findLiveByService(TENANT, SERVICE))
                .thenThrow(new IllegalStateException("boom"))
                .thenReturn(Optional.empty());
        when(contractService.createInTx(any(), any(), any(), any(), any(), any(), any())).thenReturn(published(1));

        service.applyPending(TENANT, SERVICE, REPO);

        verify(specRepository).markStatus(eq(TENANT), eq(good.id()), eq(DiscoveredSpecStatus.APPLIED), eq(SERVICE),
                any(), eq(List.of()));
    }

    private static SpecDiscoveredPayload payload(String raw) {
        return new SpecDiscoveredPayload(TENANT, UUID.randomUUID(), REPO, "openapi.yaml", "a".repeat(64),
                Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8)));
    }

    private static DiscoveredSpec spec(DiscoveredSpecStatus status, String raw) {
        return spec(status, raw, null);
    }

    private static DiscoveredSpec spec(DiscoveredSpecStatus status, String raw, UUID serviceId) {
        Instant now = Instant.now();
        return new DiscoveredSpec(UUID.randomUUID(), TENANT, UUID.randomUUID(), REPO, "openapi.yaml",
                "a".repeat(64), raw, status, serviceId, null, List.of(), now, now);
    }

    private static ApiContract contract(UUID id) {
        Instant now = Instant.now();
        return new ApiContract(id, TENANT, SERVICE, ApiContract.OPENAPI, now, now, null);
    }

    private static ContractVersion version(int number) {
        return new ContractVersion(UUID.randomUUID(), TENANT, UUID.randomUUID(), number, null, "raw", "{}",
                "b".repeat(64), false, Instant.now(), ContractVersion.SOURCE_DISCOVERY, null, null, null);
    }

    private static PublishedContract published(int number) {
        return new PublishedContract(contract(UUID.randomUUID()), version(number), null);
    }
}
