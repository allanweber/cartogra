package io.cartogra.topology.repository;

import io.cartogra.topology.domain.DependencyDrift;
import io.cartogra.topology.domain.DriftType;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DependencyDriftRepository {

    Optional<DependencyDrift> findById(UUID tenantId, UUID id);

    /** Unresolved drift records for a tenant. */
    List<DependencyDrift> findActive(UUID tenantId, int limit, int offset);

    long countActive(UUID tenantId);

    /** The still-unresolved drift record for a given edge + drift type, if one exists. */
    Optional<DependencyDrift> findActiveByEdge(UUID tenantId, UUID sourceServiceId, UUID targetServiceId,
            DriftType type);

    DependencyDrift save(DependencyDrift drift);

    void resolve(UUID tenantId, UUID id, UUID resolvedBy, Instant resolvedAt);
}
