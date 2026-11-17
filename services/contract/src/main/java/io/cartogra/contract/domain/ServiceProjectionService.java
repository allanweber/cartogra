package io.cartogra.contract.domain;

import io.cartogra.contract.infrastructure.registry.RegistryNameClient;
import io.cartogra.contract.repository.ServiceProjectionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Contract's copy of Registry service names: kept current by {@code service.registered}/{@code service.updated},
 * and filled from Registry only for services that have no name yet. Registry being down leaves the name empty
 * rather than failing the read.
 */
@Service
public class ServiceProjectionService {

    static final int MAX_LOOKUP = 100;

    private static final Logger log = LoggerFactory.getLogger(ServiceProjectionService.class);

    private final ServiceProjectionRepository repository;
    private final RegistryNameClient registry;

    public ServiceProjectionService(ServiceProjectionRepository repository, RegistryNameClient registry) {
        this.repository = repository;
        this.registry = registry;
    }

    public void recordName(UUID tenantId, UUID serviceId, String name) {
        repository.upsertNames(tenantId, Map.of(serviceId, name), Instant.now());
    }

    public Optional<String> nameOf(UUID tenantId, UUID serviceId) {
        Optional<String> known = repository.findName(tenantId, serviceId);
        if (known.isPresent()) {
            return known;
        }
        return Optional.ofNullable(fetch(tenantId, List.of(serviceId)).get(serviceId));
    }

    /** Fetches the names of the tenant's live contracts' services that have none yet, so search and sort see them. */
    public void fillMissingNames(UUID tenantId) {
        List<UUID> unnamed = repository.findUnnamed(tenantId, MAX_LOOKUP);
        if (!unnamed.isEmpty()) {
            fetch(tenantId, unnamed);
        }
    }

    private Map<UUID, String> fetch(UUID tenantId, List<UUID> serviceIds) {
        Map<UUID, String> names;
        try {
            names = registry.findNames(tenantId, serviceIds);
        } catch (RestClientException e) {
            log.warn("Registry name lookup failed for {} services: {}", serviceIds.size(), e.getMessage());
            return Map.of();
        }
        if (!names.isEmpty()) {
            repository.upsertNames(tenantId, names, Instant.now());
        }
        return names;
    }
}
