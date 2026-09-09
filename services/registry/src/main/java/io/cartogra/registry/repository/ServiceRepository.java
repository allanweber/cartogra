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

    Optional<Service> findByName(UUID tenantId, String name);

    /** Looks up by the K8s cluster/namespace/name triple — the stable identity for kubernetes-sourced services. */
    Optional<Service> findByK8sIdentity(UUID tenantId, String k8sCluster, String k8sNamespace, String name);

    /**
     * Name fallback for first-contact K8s discovery. Only matches rows that have NOT yet been
     * claimed by any K8s identity (source != 'kubernetes' OR k8s_cluster IS NULL), preventing
     * two distinct K8s services with the same name from merging into one row.
     */
    Optional<Service> findByNameForK8sClaim(UUID tenantId, String name);

    /** Cross-tenant query; excludes K8s-sourced services and deleted rows. */
    List<Service> findAllWithHealthEndpoint();

    /**
     * Cross-tenant, paginated, active-only. Backs the internal {@code /internal/services}
     * endpoint used by Topology's admin backfill (walks every tenant's services once for
     * tenants that predate its Kafka consumer).
     */
    List<Service> findAllActive(int limit, int offset);

    /** Total active (non-deleted) service count across every tenant — pairs with {@link #findAllActive}. */
    long countActive();

    void updateHealth(UUID tenantId, UUID id, ServiceHealthStatus status, Instant checkedAt);

    List<String> findDistinctTechStacks(UUID tenantId);

    /** Returns a map of connection_id -> service count for all active services belonging to the tenant. */
    java.util.Map<UUID, Long> countByConnectionId(UUID tenantId);
}
