package io.cartogra.contract.domain;

import io.cartogra.common.identity.Actor;
import io.cartogra.contract.domain.exception.BaselineNotFoundException;
import io.cartogra.contract.domain.exception.CheckServiceNotFoundException;
import io.cartogra.contract.domain.exception.ServiceAmbiguousException;
import io.cartogra.contract.repository.ContractCheckRepository;
import io.cartogra.contract.repository.ContractRepository;
import io.cartogra.contract.repository.ContractVersionRepository;
import io.cartogra.contract.repository.ServiceProjectionRepository;
import io.cartogra.web.tenant.TenantContext;
import org.jspecify.annotations.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Compares a candidate spec with the Baseline of a service for CI. A check stores one {@link ContractCheck} row and
 * never a Contract Version, an audit event, or a Kafka event.
 */
@Service
public class ContractCheckService {

    private static final Logger log = LoggerFactory.getLogger(ContractCheckService.class);

    private final ServiceProjectionRepository serviceRepository;
    private final ContractRepository contractRepository;
    private final ContractVersionRepository versionRepository;
    private final ContractCheckRepository checkRepository;
    private final ConsumerService consumerService;
    private final SpecParser specParser;
    private final BreakingChangeEngine engine;
    private final BreakingChangeNotifier notifier;

    public ContractCheckService(ServiceProjectionRepository serviceRepository,
                                ContractRepository contractRepository,
                                ContractVersionRepository versionRepository,
                                ContractCheckRepository checkRepository,
                                ConsumerService consumerService,
                                SpecParser specParser,
                                BreakingChangeEngine engine,
                                BreakingChangeNotifier notifier) {
        this.serviceRepository = serviceRepository;
        this.contractRepository = contractRepository;
        this.versionRepository = versionRepository;
        this.checkRepository = checkRepository;
        this.consumerService = consumerService;
        this.specParser = specParser;
        this.engine = engine;
        this.notifier = notifier;
    }

    /** {@code serviceRef} is a service id, or a service name that is matched not case-sensitive. */
    @PreAuthorize("hasRole('CI_CHECK')")
    public CheckResult check(UUID tenantId, Actor actor, String serviceRef, String spec) {
        UUID apiKeyId = actor.apiKeyId();
        if (apiKeyId == null) {
            throw new AccessDeniedException("An API key is required");
        }
        UUID serviceId = resolveService(tenantId, serviceRef.trim());
        ApiContract contract = contractRepository.findLiveByService(tenantId, serviceId)
                .orElseThrow(() -> new BaselineNotFoundException(serviceId));
        ContractVersion baseline = versionRepository.findLatest(tenantId, contract.id())
                .orElseThrow(() -> new BaselineNotFoundException(serviceId));
        ParsedSpec candidate = specParser.parse(spec);
        Compatibility compatibility = engine.compare(baseline.specCanonical(), candidate.canonical());
        List<Consumer> consumers = compatibility.isBreaking()
                ? consumerService.allConsumersOfService(tenantId, serviceId)
                : List.of();
        UUID checkId = UUID.randomUUID();
        checkRepository.insert(new ContractCheck(checkId, tenantId, serviceId, contract.id(),
                baseline.id(), candidate.hash(), compatibility.isBreaking(), compatibility.changes(),
                consumers.stream().map(Consumer::serviceId).toList(), apiKeyId, Instant.now()));
        CheckResult result = new CheckResult(compatibility.isBreaking(), baseline.versionNumber(),
                compatibility.changes(), consumers);
        if (compatibility.isBreaking()) {
            String serviceName = serviceRepository.findName(tenantId, serviceId).orElse(serviceId.toString());
            UUID contractId = contract.id();
            Thread.ofVirtual().start(() -> {
                try {
                    TenantContext.runAs(tenantId, () -> notifier.notifyBreaking(tenantId, serviceId, contractId,
                            serviceName, checkId, result));
                } catch (Exception _) {
                    log.warn("Notification delivery failed for check {}", checkId);
                }
            });
        }
        return result;
    }

    private UUID resolveService(UUID tenantId, String ref) {
        UUID id = asUuid(ref);
        if (id != null) {
            return serviceRepository.findLiveId(tenantId, id)
                    .orElseThrow(() -> new CheckServiceNotFoundException(ref));
        }
        List<UUID> matches = serviceRepository.findLiveIdsByName(tenantId, ref, 2);
        if (matches.isEmpty()) {
            throw new CheckServiceNotFoundException(ref);
        }
        if (matches.size() > 1) {
            throw new ServiceAmbiguousException(ref);
        }
        return matches.getFirst();
    }

    private static @Nullable UUID asUuid(String ref) {
        try {
            return UUID.fromString(ref);
        } catch (IllegalArgumentException _) {
            return null;
        }
    }
}
