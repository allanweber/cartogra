package io.cartogra.topology.repository;

import io.cartogra.topology.domain.DependencyDrift;
import io.cartogra.topology.domain.DriftResolution;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DependencyDriftRepository {

    Optional<DependencyDrift> findById(UUID tenantId, UUID id);

    /** Unresolved drift records for a tenant. */
    List<DependencyDrift> findActive(UUID tenantId, int limit, int offset);

    long countActive(UUID tenantId);

    DependencyDrift save(DependencyDrift drift);

    /** Closes an unresolved record; returns false when it was already resolved. */
    boolean resolve(UUID tenantId, UUID id, @Nullable UUID resolvedBy, DriftResolution resolution, Instant resolvedAt);

    /** Every unresolved record of the tenant, unpaged — the sweep's working set. */
    List<DependencyDrift> findAllActive(UUID tenantId);

    List<DependencyDrift> findDismissed(UUID tenantId);

    /** Inserts a fresh unresolved record; returns false when one already exists for the edge + type. */
    boolean insertIfAbsent(DependencyDrift drift);

    /** Tenants with live dependencies or unresolved drift: the sweep's work list. */
    List<UUID> findTenantIdsToSweep();
}
