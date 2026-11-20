package io.cartogra.contract.domain;

import io.cartogra.common.identity.Actor;
import io.cartogra.contract.domain.exception.ContractAlreadyExistsException;
import io.cartogra.contract.domain.exception.InvalidSpecException;
import io.cartogra.contract.domain.exception.ServiceDeletedException;
import io.cartogra.contract.repository.ContractRepository;
import io.cartogra.contract.repository.DiscoveredSpecRepository;
import io.cartogra.contract.repository.ServiceProjectionRepository;
import io.cartogra.web.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Applies the specs that ingestion discovers in SCM repositories (story 5.8). It resolves the repository to a service
 * through the projection or Registry, keeps the spec PENDING while no service matches, and publishes it as a
 * contract version for {@link Actor#SYSTEM}. It has no role check, because it runs on Kafka threads only.
 */
@Service
public class SpecDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(SpecDiscoveryService.class);

    private final DiscoveredSpecRepository specRepository;
    private final ContractRepository contractRepository;
    private final ServiceProjectionRepository projectionRepository;
    private final ServiceProjectionService projectionService;
    private final ContractService contractService;
    private final SpecParser specParser;
    private final TransactionTemplate transactionTemplate;

    public SpecDiscoveryService(DiscoveredSpecRepository specRepository,
                                ContractRepository contractRepository,
                                ServiceProjectionRepository projectionRepository,
                                ServiceProjectionService projectionService,
                                ContractService contractService,
                                SpecParser specParser,
                                PlatformTransactionManager transactionManager) {
        this.specRepository = specRepository;
        this.contractRepository = contractRepository;
        this.projectionRepository = projectionRepository;
        this.projectionService = projectionService;
        this.contractService = contractService;
        this.specParser = specParser;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Stores and applies one {@code spec.discovered}. A spec equal to the last spec seen at its repository and path
     * changes nothing while the service that got it is live. If that service is tombstoned, or the last spec is
     * SKIPPED, the spec becomes PENDING again and goes to the live service of the repository. A different spec, also
     * a revert, is applied again.
     */
    public void ingest(SpecDiscoveredPayload payload) {
        TenantContext.runAs(payload.tenantId(), () -> {
            DiscoveredSpec spec = transactionTemplate.execute(_ -> {
                DiscoveredSpec latest = specRepository.recordLatest(newSpec(payload));
                return lostItsService(latest) ? specRepository.reopenLatest(latest) : latest;
            });
            if (spec.status() != DiscoveredSpecStatus.PENDING) {
                log.debug("Spec {} of {} is already {}", spec.filePath(), spec.repositoryFullName(), spec.status());
                return;
            }
            Optional<UUID> serviceId = resolve(spec.tenantId(), spec.repositoryFullName());
            if (serviceId.isEmpty()) {
                log.info("Spec {} of {} stays PENDING: no service has this external id",
                        spec.filePath(), spec.repositoryFullName());
                return;
            }
            apply(spec, serviceId.get());
        });
    }

    /**
     * Applies the PENDING specs of the repository {@code externalId}, now that Registry announced its service. A spec
     * that fails stays PENDING, so the service event is not blocked.
     */
    public void applyPending(UUID tenantId, UUID serviceId, String externalId) {
        TenantContext.runAs(tenantId, () -> {
            for (DiscoveredSpec spec : specRepository.findPending(tenantId, externalId)) {
                try {
                    apply(spec, serviceId);
                } catch (RuntimeException e) {
                    log.warn("Failed to apply the PENDING spec {} of {}: {}",
                            spec.filePath(), spec.repositoryFullName(), e.getMessage(), e);
                }
            }
        });
    }

    DiscoveredSpecStatus apply(DiscoveredSpec spec, UUID serviceId) {
        if (projectionRepository.isDeleted(spec.tenantId(), serviceId)) {
            return mark(spec, DiscoveredSpecStatus.SKIPPED, serviceId, List.of());
        }
        ParsedSpec parsed;
        try {
            parsed = specParser.parse(spec.specRaw());
        } catch (InvalidSpecException e) {
            return mark(spec, DiscoveredSpecStatus.REJECTED, serviceId, e.messages());
        }
        try {
            return applyRetryingARace(spec, serviceId, parsed);
        } catch (ServiceDeletedException e) {
            return mark(spec, DiscoveredSpecStatus.SKIPPED, serviceId, List.of());
        }
    }

    private DiscoveredSpecStatus applyRetryingARace(DiscoveredSpec spec, UUID serviceId, ParsedSpec parsed) {
        try {
            return applyOnce(spec, serviceId, parsed);
        } catch (DuplicateKeyException | ContractAlreadyExistsException e) {
            log.info("A contract for service {} was created at the same time; publish a version instead",
                    serviceId);
            return applyOnce(spec, serviceId, parsed);
        }
    }

    private DiscoveredSpecStatus applyOnce(DiscoveredSpec spec, UUID serviceId, ParsedSpec parsed) {
        return transactionTemplate.execute(_ -> {
            UUID tenantId = spec.tenantId();
            if (specRepository.lockPending(tenantId, spec.id()).isEmpty()) {
                return DiscoveredSpecStatus.PENDING;
            }
            if (projectionRepository.isDeleted(tenantId, serviceId)) {
                throw new ServiceDeletedException(serviceId);
            }
            Optional<ApiContract> contract = contractRepository.findLiveByService(tenantId, serviceId);
            DiscoveredSpecStatus status;
            ContractVersion version;
            if (contract.isEmpty()) {
                version = contractService.createInTx(tenantId, Actor.SYSTEM, serviceId, spec.specRaw(), parsed,
                        ContractVersion.SOURCE_DISCOVERY, spec.id()).latestVersion();
                status = DiscoveredSpecStatus.APPLIED;
            } else {
                VersionPublication publication = contractService.publishInTx(tenantId, Actor.SYSTEM,
                        contract.get().id(), spec.specRaw(), parsed, ContractVersion.SOURCE_DISCOVERY, spec.id());
                version = publication.version();
                status = publication.created() ? DiscoveredSpecStatus.APPLIED : DiscoveredSpecStatus.UNCHANGED;
            }
            specRepository.markStatus(tenantId, spec.id(), status, serviceId, version.id(), List.of());
            log.info("Spec {} of {} is {} as version {} of contract {}", spec.filePath(), spec.repositoryFullName(),
                    status, version.versionNumber(), version.contractId());
            return status;
        });
    }

    private DiscoveredSpecStatus mark(DiscoveredSpec spec, DiscoveredSpecStatus status, UUID serviceId,
                                      List<String> errors) {
        transactionTemplate.executeWithoutResult(_ ->
                specRepository.markStatus(spec.tenantId(), spec.id(), status, serviceId, null, errors));
        log.info("Spec {} of {} is {}", spec.filePath(), spec.repositoryFullName(), status);
        return status;
    }

    private boolean lostItsService(DiscoveredSpec spec) {
        return switch (spec.status()) {
            case SKIPPED -> true;
            case APPLIED, UNCHANGED -> spec.serviceId() != null
                    && projectionRepository.isDeleted(spec.tenantId(), spec.serviceId());
            case PENDING, REJECTED, SUPERSEDED -> false;
        };
    }

    private Optional<UUID> resolve(UUID tenantId, String externalId) {
        Optional<UUID> known = projectionService.findLiveByExternalId(tenantId, externalId);
        if (known.isPresent()) {
            return known;
        }
        Optional<UUID> found = projectionService.findInRegistryByExternalId(tenantId, externalId);
        found.ifPresent(serviceId -> projectionService.recordExternalId(tenantId, serviceId, externalId));
        return found;
    }

    private static DiscoveredSpec newSpec(SpecDiscoveredPayload payload) {
        String raw = new String(Base64.getDecoder().decode(payload.contentB64()), StandardCharsets.UTF_8);
        Instant now = Instant.now();
        return new DiscoveredSpec(UUID.randomUUID(), payload.tenantId(), payload.connectionId(),
                payload.repositoryFullName(), payload.filePath(), payload.contentSha256(), raw,
                DiscoveredSpecStatus.PENDING, null, null, List.of(), now, now);
    }
}
