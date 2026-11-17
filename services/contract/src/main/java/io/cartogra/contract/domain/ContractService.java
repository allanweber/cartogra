package io.cartogra.contract.domain;

import io.cartogra.common.api.PageResult;
import io.cartogra.common.audit.AuditEvent;
import io.cartogra.common.audit.AuditEventPort;
import io.cartogra.common.audit.AuditKind;
import io.cartogra.common.identity.Actor;
import io.cartogra.common.identity.Roles;
import io.cartogra.contract.api.dto.ContractRequest;
import io.cartogra.contract.domain.exception.ContractAlreadyExistsException;
import io.cartogra.contract.domain.exception.ContractNotFoundException;
import io.cartogra.contract.domain.exception.MissingFieldException;
import io.cartogra.contract.domain.exception.ServiceDeletedException;
import io.cartogra.contract.domain.exception.VersionNotFoundException;
import org.jspecify.annotations.Nullable;
import io.cartogra.contract.repository.ContractRepository;
import io.cartogra.contract.repository.ContractVersionRepository;
import io.cartogra.contract.repository.ServiceProjectionRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ContractService {

    static final int MAX_PAGE_SIZE = 100;

    private final ContractRepository contractRepository;
    private final ContractVersionRepository versionRepository;
    private final ServiceProjectionRepository serviceProjectionRepository;
    private final ServiceProjectionService serviceProjectionService;
    private final SpecParser specParser;
    private final SpecDiffer specDiffer;
    private final AuditEventPort auditEventPort;
    private final TransactionTemplate transactionTemplate;

    public ContractService(ContractRepository contractRepository,
                           ContractVersionRepository versionRepository,
                           ServiceProjectionRepository serviceProjectionRepository,
                           ServiceProjectionService serviceProjectionService,
                           SpecParser specParser,
                           SpecDiffer specDiffer,
                           AuditEventPort auditEventPort,
                           PlatformTransactionManager transactionManager) {
        this.contractRepository = contractRepository;
        this.versionRepository = versionRepository;
        this.serviceProjectionRepository = serviceProjectionRepository;
        this.serviceProjectionService = serviceProjectionService;
        this.specParser = specParser;
        this.specDiffer = specDiffer;
        this.auditEventPort = auditEventPort;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @PreAuthorize(Roles.CAN_WRITE)
    public PublishedContract create(UUID tenantId, Actor actor, ContractRequest request) {
        UUID serviceId = request.serviceId();
        if (serviceId == null) {
            throw new MissingFieldException("serviceId");
        }
        ParsedSpec parsed = specParser.parse(request.spec());
        PublishedContract created;
        try {
            created = transactionTemplate.execute(_ -> {
                requireLiveService(tenantId, serviceId);
                contractRepository.findLiveByService(tenantId, serviceId).ifPresent(existing -> {
                    throw new ContractAlreadyExistsException(serviceId, existing.id());
                });
                Instant now = Instant.now();
                var contract = new ApiContract(UUID.randomUUID(), tenantId, serviceId, ApiContract.OPENAPI,
                        now, now, null);
                contractRepository.insert(contract);
                ContractVersion version = newVersion(contract, 1, request.spec(), parsed, now);
                versionRepository.insert(version);
                audit(contract, "created", actor, version);
                return new PublishedContract(contract, version, null);
            });
        } catch (DuplicateKeyException e) {
            UUID existingId = contractRepository.findLiveByService(tenantId, serviceId)
                    .map(ApiContract::id)
                    .orElseThrow(() -> e);
            throw new ContractAlreadyExistsException(serviceId, existingId);
        }
        return new PublishedContract(created.contract(), created.latestVersion(),
                serviceProjectionService.nameOf(tenantId, serviceId).orElse(null));
    }

    /** Stores the spec as the next version unless it hashes the same as the latest one, which is returned instead. */
    @PreAuthorize(Roles.CAN_WRITE)
    public VersionPublication publishVersion(UUID tenantId, Actor actor, UUID contractId, ContractRequest request) {
        ParsedSpec parsed = specParser.parse(request.spec());
        return transactionTemplate.execute(_ -> {
            ApiContract contract = contractRepository.lockLive(tenantId, contractId)
                    .orElseThrow(() -> new ContractNotFoundException(contractId));
            requireLiveService(tenantId, contract.serviceId());
            ContractVersion latest = versionRepository.findLatest(tenantId, contractId)
                    .orElseThrow(() -> new IllegalStateException("Contract " + contractId + " has no version"));
            if (latest.specHash().equals(parsed.hash())) {
                return new VersionPublication(latest, false);
            }
            Instant now = Instant.now();
            ContractVersion version = newVersion(contract, latest.versionNumber() + 1, request.spec(), parsed, now);
            versionRepository.insert(version);
            contractRepository.touch(tenantId, contractId, now);
            audit(contract, "version_published", actor, version);
            return new VersionPublication(version, true);
        });
    }

    public PublishedContract get(UUID tenantId, UUID contractId) {
        ApiContract contract = contractRepository.findLive(tenantId, contractId)
                .orElseThrow(() -> new ContractNotFoundException(contractId));
        ContractVersion latest = versionRepository.findLatest(tenantId, contractId)
                .orElseThrow(() -> new ContractNotFoundException(contractId));
        return new PublishedContract(contract, latest,
                serviceProjectionService.nameOf(tenantId, contract.serviceId()).orElse(null));
    }

    public PageResult<ContractSummary> list(UUID tenantId, @Nullable String q, int limit, int offset) {
        int size = clampLimit(limit);
        int from = Math.max(offset, 0);
        serviceProjectionService.fillMissingNames(tenantId);
        return PageResult.of(contractRepository.listLive(tenantId, q, size, from),
                contractRepository.countLive(tenantId, q), size, from);
    }

    public PageResult<VersionHeader> listVersions(UUID tenantId, UUID contractId, int limit, int offset) {
        requireLiveContract(tenantId, contractId);
        int size = clampLimit(limit);
        int from = Math.max(offset, 0);
        return PageResult.of(versionRepository.list(tenantId, contractId, size, from),
                versionRepository.count(tenantId, contractId), size, from);
    }

    /** Version {@code versionNumber} against the version before it; version 1 is diffed against an empty spec. */
    public VersionDiff diff(UUID tenantId, UUID contractId, int versionNumber) {
        requireLiveContract(tenantId, contractId);
        ContractVersion to = versionRepository.findByNumber(tenantId, contractId, versionNumber)
                .orElseThrow(() -> new VersionNotFoundException(contractId, versionNumber));
        ContractVersion from = versionNumber == 1 ? null
                : versionRepository.findByNumber(tenantId, contractId, versionNumber - 1)
                        .orElseThrow(() -> new IllegalStateException(
                                "Contract " + contractId + " is missing version " + (versionNumber - 1)));
        return specDiffer.diff(from, to);
    }

    /** Applies Registry's {@code service.deleted}; replaying it changes nothing and audits nothing. */
    public void deleteForService(UUID tenantId, UUID serviceId) {
        transactionTemplate.executeWithoutResult(_ -> {
            Instant now = Instant.now();
            serviceProjectionRepository.tombstone(tenantId, serviceId, now);
            List<UUID> contractIds = contractRepository.softDeleteByService(tenantId, serviceId, now);
            versionRepository.softDeleteByContracts(tenantId, contractIds, now);
            contractIds.forEach(id -> auditEventPort.record(AuditEvent.of(tenantId, AuditKind.CONTRACT, id,
                    "deleted", Actor.SYSTEM, Map.of("serviceId", serviceId))));
        });
    }

    private void requireLiveContract(UUID tenantId, UUID contractId) {
        if (contractRepository.findLive(tenantId, contractId).isEmpty()) {
            throw new ContractNotFoundException(contractId);
        }
    }

    private static int clampLimit(int limit) {
        return Math.clamp(limit, 1, MAX_PAGE_SIZE);
    }

    private void requireLiveService(UUID tenantId, UUID serviceId) {
        if (serviceProjectionRepository.isDeleted(tenantId, serviceId)) {
            throw new ServiceDeletedException(serviceId);
        }
    }

    private static ContractVersion newVersion(ApiContract contract, int number, String raw, ParsedSpec parsed,
                                              Instant now) {
        return new ContractVersion(UUID.randomUUID(), contract.tenantId(), contract.id(), number, parsed.label(),
                raw, parsed.canonical(), parsed.hash(), now);
    }

    private void audit(ApiContract contract, String action, Actor actor, ContractVersion version) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("serviceId", contract.serviceId());
        payload.put("versionNumber", version.versionNumber());
        if (version.label() != null) {
            payload.put("label", version.label());
        }
        payload.put("specHash", version.specHash());
        auditEventPort.record(AuditEvent.of(contract.tenantId(), AuditKind.CONTRACT, contract.id(), action, actor,
                payload));
    }
}
