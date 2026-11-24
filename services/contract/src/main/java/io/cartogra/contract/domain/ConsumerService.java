package io.cartogra.contract.domain;

import io.cartogra.common.api.PageResult;
import io.cartogra.contract.domain.exception.ContractNotFoundException;
import io.cartogra.contract.repository.ContractRepository;
import io.cartogra.contract.repository.InboundEdgeRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Consumers of a contract, derived from topology's edges: a service with a live declared or observed edge into the
 * contract's service over an API protocol. The edges are a projection of {@code dependency.changed}, keyed by edge id.
 */
@Service
public class ConsumerService {

    static final String DECLARED = "dependency.declared";
    static final String OBSERVED = "dependency.observed";
    static final String REMOVED = "dependency.removed";

    /** An OpenAPI change reaches callers of the API; kafka and db edges do not call it. */
    static final Set<String> API_PROTOCOLS = Set.of("http", "grpc");

    static final int MAX_PAGE_SIZE = 100;
    /** Upper bound of the consumers that one breaking-change check reads. */
    static final int MAX_CHECK_CONSUMERS = 500;

    private final InboundEdgeRepository edgeRepository;
    private final ContractRepository contractRepository;
    private final ServiceProjectionService serviceProjectionService;

    public ConsumerService(InboundEdgeRepository edgeRepository, ContractRepository contractRepository,
            ServiceProjectionService serviceProjectionService) {
        this.edgeRepository = edgeRepository;
        this.contractRepository = contractRepository;
        this.serviceProjectionService = serviceProjectionService;
    }

    /** Returns false for an event type this projection ignores. Replays change nothing. */
    public boolean apply(UUID tenantId, String eventType, InboundEdge edge) {
        switch (eventType) {
            case DECLARED, OBSERVED -> edgeRepository.upsert(tenantId, edge, Instant.now());
            case REMOVED -> edgeRepository.markRemoved(tenantId, edge, Instant.now());
            default -> {
                return false;
            }
        }
        return true;
    }

    public PageResult<Consumer> consumersOf(UUID tenantId, UUID contractId, int limit, int offset) {
        ApiContract contract = contractRepository.findLive(tenantId, contractId)
                .orElseThrow(() -> new ContractNotFoundException(contractId));
        return consumersOfService(tenantId, contract.serviceId(), limit, offset);
    }

    public PageResult<Consumer> consumersOfService(UUID tenantId, UUID serviceId, int limit, int offset) {
        int size = Math.clamp(limit, 1, MAX_PAGE_SIZE);
        int from = Math.max(offset, 0);
        List<Consumer> page = edgeRepository.findConsumers(tenantId, serviceId, API_PROTOCOLS, size, from);
        long total = edgeRepository.countConsumers(tenantId, serviceId, API_PROTOCOLS);
        return PageResult.of(withNames(tenantId, page), total, size, from);
    }

    /** The consumers of the service, in one edge query, bounded by {@link #MAX_CHECK_CONSUMERS}. */
    public List<Consumer> allConsumersOfService(UUID tenantId, UUID serviceId) {
        return withNames(tenantId, edgeRepository.findConsumers(tenantId, serviceId, API_PROTOCOLS,
                MAX_CHECK_CONSUMERS, 0));
    }

    private List<Consumer> withNames(UUID tenantId, List<Consumer> page) {
        List<UUID> unnamed = page.stream().filter(c -> c.serviceName() == null).map(Consumer::serviceId).toList();
        if (unnamed.isEmpty()) {
            return page;
        }
        Map<UUID, String> names = serviceProjectionService.fetchNames(tenantId, unnamed);
        return page.stream()
                .map(c -> c.serviceName() == null ? c.withName(names.get(c.serviceId())) : c)
                .toList();
    }
}
