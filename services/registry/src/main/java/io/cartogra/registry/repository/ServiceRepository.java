package io.cartogra.registry.repository;

import io.cartogra.registry.domain.Service;
import io.cartogra.registry.domain.ServiceHealthStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ServiceRepository {

    Optional<Service> findById(UUID tenantId, UUID id);

    List<Service> findAll(UUID tenantId, ServiceFilter filter, int limit, int offset);

    long count(UUID tenantId, ServiceFilter filter);

    List<Service> findOrphaned(UUID tenantId, int limit, int offset);

    Service save(Service service);

    void softDelete(UUID tenantId, UUID id);

    boolean existsByName(UUID tenantId, String name, UUID excludeId);

    Optional<Service> findByRepositoryPath(UUID tenantId, String repositoryPath);

    Optional<Service> findByExternalId(UUID tenantId, String externalId);

    Optional<Service> upsertDiscovered(ServiceDiscoveryCommand command);

    /** Cross-tenant query; excludes K8s-sourced services and deleted rows. */
    List<Service> findAllWithHealthEndpoint();

    void updateHealth(UUID tenantId, UUID id, ServiceHealthStatus status, Instant checkedAt);

    List<String> findDistinctTechStacks(UUID tenantId);

    /** Returns a map of connection_id -> service count for all active services belonging to the tenant. */
    java.util.Map<UUID, Long> countByConnectionId(UUID tenantId);
}
