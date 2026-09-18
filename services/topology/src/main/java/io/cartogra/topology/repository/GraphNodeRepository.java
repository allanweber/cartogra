package io.cartogra.topology.repository;

import io.cartogra.topology.domain.GraphNode;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface GraphNodeRepository {

    /** Insert-or-update by (tenantId, serviceId); a matching soft-deleted row is revived. */
    void upsert(GraphNodeUpsert command);

    void softDelete(UUID tenantId, UUID serviceId, Instant deletedAt);

    Optional<GraphNode> findByServiceId(UUID tenantId, UUID serviceId);
}
