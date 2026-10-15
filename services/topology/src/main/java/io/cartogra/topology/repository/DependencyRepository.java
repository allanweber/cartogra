package io.cartogra.topology.repository;

import io.cartogra.topology.domain.Dependency;
import io.cartogra.topology.domain.DependencyProtocol;
import io.cartogra.topology.domain.DependencyType;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DependencyRepository {

    Optional<Dependency> findById(UUID tenantId, UUID id);

    List<Dependency> findAll(UUID tenantId, DependencyFilter filter, int limit, int offset);

    long count(UUID tenantId, DependencyFilter filter);

    /** Every non-deleted edge touching a service, either as source or target. */
    List<Dependency> findByService(UUID tenantId, UUID serviceId);

    Dependency save(Dependency dependency);

    void softDelete(UUID tenantId, UUID id);

    /** Soft-deletes every edge (declared or observed) touching a service — used on service.deleted. */
    void softDeleteAllForService(UUID tenantId, UUID serviceId);

    /**
     * Looks up the edge identity used for observed-edge upserts: same tenant, source, target,
     * type, and protocol. Distinct protocols between the same two services are distinct edges.
     */
    Optional<Dependency> findByEdgeIdentity(UUID tenantId, UUID sourceServiceId, UUID targetServiceId,
            DependencyType type, DependencyProtocol protocol);

    /**
     * Inserts an observed edge or refreshes {@code last_seen_at} on the existing one. The refresh
     * is throttled: it only happens when the stored value is more than a minute older than
     * {@code seenAt}, otherwise the outcome is {@link UpsertOutcome#UNCHANGED}.
     */
    UpsertOutcome upsertObserved(UUID tenantId, UUID sourceServiceId, UUID targetServiceId,
            DependencyProtocol protocol, Instant seenAt);
}
