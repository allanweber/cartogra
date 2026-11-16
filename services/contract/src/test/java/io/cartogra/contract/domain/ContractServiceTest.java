package io.cartogra.contract.domain;

import io.cartogra.common.audit.AuditEvent;
import io.cartogra.common.audit.AuditEventPort;
import io.cartogra.common.identity.Actor;
import io.cartogra.contract.api.dto.ContractRequest;
import io.cartogra.contract.domain.exception.ContractAlreadyExistsException;
import io.cartogra.contract.domain.exception.MissingFieldException;
import io.cartogra.contract.domain.exception.ServiceDeletedException;
import io.cartogra.contract.repository.ContractRepository;
import io.cartogra.contract.repository.ContractVersionRepository;
import io.cartogra.contract.repository.DeletedServiceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContractServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID SERVICE = UUID.randomUUID();
    private static final Actor USER = Actor.user(UUID.randomUUID());

    @Mock ContractRepository contractRepository;
    @Mock ContractVersionRepository versionRepository;
    @Mock DeletedServiceRepository deletedServiceRepository;
    @Mock AuditEventPort auditEventPort;
    @Mock PlatformTransactionManager transactionManager;

    private final SpecParser specParser = new SpecParser();
    private ContractService service;

    @BeforeEach
    void setUp() {
        service = new ContractService(contractRepository, versionRepository, deletedServiceRepository, specParser,
                auditEventPort, transactionManager);
    }

    @Test
    void createStoresVersionOneAndAuditsCreated() {
        when(contractRepository.findLiveByService(TENANT, SERVICE)).thenReturn(Optional.empty());

        PublishedContract created = service.create(TENANT, USER, new ContractRequest(SERVICE, SpecParserTest.YAML_30));

        assertThat(created.latestVersion().versionNumber()).isEqualTo(1);
        assertThat(created.latestVersion().label()).isEqualTo("1.2.0");
        verify(versionRepository).insert(created.latestVersion());
        AuditEvent event = capturedAudit();
        assertThat(event.action()).isEqualTo("created");
        assertThat(event.entityType()).isEqualTo("contract");
        assertThat(event.payload()).containsEntry("serviceId", SERVICE).containsEntry("versionNumber", 1)
                .containsKey("specHash").doesNotContainKey("spec");
    }

    @Test
    void createRequiresServiceId() {
        assertThatThrownBy(() -> service.create(TENANT, USER, new ContractRequest(null, SpecParserTest.YAML_30)))
                .isInstanceOf(MissingFieldException.class);
        verifyNoInteractions(contractRepository);
    }

    @Test
    void createForServiceWithContractConflictsWithExistingId() {
        UUID existingId = UUID.randomUUID();
        when(contractRepository.findLiveByService(TENANT, SERVICE)).thenReturn(Optional.of(contract(existingId)));

        assertThatThrownBy(() -> service.create(TENANT, USER, new ContractRequest(SERVICE, SpecParserTest.YAML_30)))
                .isInstanceOfSatisfying(ContractAlreadyExistsException.class,
                        e -> assertThat(e.contractId()).isEqualTo(existingId));
        verify(contractRepository, never()).insert(any());
        verifyNoInteractions(auditEventPort);
    }

    @Test
    void createForDeletedServiceIsRejected() {
        when(deletedServiceRepository.exists(TENANT, SERVICE)).thenReturn(true);

        assertThatThrownBy(() -> service.create(TENANT, USER, new ContractRequest(SERVICE, SpecParserTest.YAML_30)))
                .isInstanceOf(ServiceDeletedException.class);
    }

    @Test
    void publishingTheLatestSpecAgainStoresNothing() {
        UUID id = UUID.randomUUID();
        ContractVersion latest = version(id, 3, specParser.parse(SpecParserTest.JSON_30_REORDERED));
        when(contractRepository.lockLive(TENANT, id)).thenReturn(Optional.of(contract(id)));
        when(versionRepository.findLatest(TENANT, id)).thenReturn(Optional.of(latest));

        VersionPublication result = service.publishVersion(TENANT, USER, id,
                new ContractRequest(null, SpecParserTest.YAML_30));

        assertThat(result.created()).isFalse();
        assertThat(result.version()).isEqualTo(latest);
        verify(versionRepository, never()).insert(any());
        verifyNoInteractions(auditEventPort);
    }

    @Test
    void revertingToAnOlderSpecCreatesTheNextVersion() {
        UUID id = UUID.randomUUID();
        String changed = SpecParserTest.YAML_30.replace("/payments:", "/refunds:");
        ContractVersion latest = version(id, 2, specParser.parse(changed));
        when(contractRepository.lockLive(TENANT, id)).thenReturn(Optional.of(contract(id)));
        when(versionRepository.findLatest(TENANT, id)).thenReturn(Optional.of(latest));

        VersionPublication result = service.publishVersion(TENANT, USER, id,
                new ContractRequest(null, SpecParserTest.YAML_30));

        assertThat(result.created()).isTrue();
        assertThat(result.version().versionNumber()).isEqualTo(3);
        verify(contractRepository).touch(eq(TENANT), eq(id), any());
        assertThat(capturedAudit().action()).isEqualTo("version_published");
    }

    @Test
    void deleteForServiceAuditsEachDeletedContractAsSystem() {
        UUID id = UUID.randomUUID();
        when(contractRepository.softDeleteByService(eq(TENANT), eq(SERVICE), any())).thenReturn(List.of(id));

        service.deleteForService(TENANT, SERVICE);

        verify(deletedServiceRepository).insert(eq(TENANT), eq(SERVICE), any());
        verify(versionRepository).softDeleteByContracts(eq(TENANT), eq(List.of(id)), any());
        AuditEvent event = capturedAudit();
        assertThat(event.action()).isEqualTo("deleted");
        assertThat(event.entityId()).isEqualTo(id);
        assertThat(event.actorId()).isNull();
    }

    @Test
    void replayedDeleteAuditsNothing() {
        when(contractRepository.softDeleteByService(eq(TENANT), eq(SERVICE), any())).thenReturn(List.of());

        service.deleteForService(TENANT, SERVICE);

        verify(auditEventPort, never()).record(any());
    }

    private AuditEvent capturedAudit() {
        var captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditEventPort, times(1)).record(captor.capture());
        return captor.getValue();
    }

    private static ApiContract contract(UUID id) {
        Instant now = Instant.now();
        return new ApiContract(id, TENANT, SERVICE, ApiContract.OPENAPI, now, now, null);
    }

    private static ContractVersion version(UUID contractId, int number, ParsedSpec parsed) {
        return new ContractVersion(UUID.randomUUID(), TENANT, contractId, number, parsed.label(), "raw",
                parsed.canonical(), parsed.hash(), Instant.now());
    }
}
