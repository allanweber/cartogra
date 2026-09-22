package io.cartogra.topology.repository;

import io.cartogra.topology.domain.GraphNode;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GraphNodeRepository {

    /** Insert-or-update by (tenantId, serviceId); a matching soft-deleted row is revived. */
    void upsert(GraphNodeUpsert command);

    void softDelete(UUID tenantId, UUID serviceId, Instant deletedAt);

    Optional<GraphNode> findByServiceId(UUID tenantId, UUID serviceId);

    /** Live nodes for the tenant, optionally scoped to a team, ordered by name; up to {@code limit} rows. */
    List<GraphNode> findForGraph(UUID tenantId, @Nullable UUID teamId, int limit);

    /** Live nodes matching any of the given service ids, ordered by name; up to {@code limit} rows. */
    List<GraphNode> findByServiceIds(UUID tenantId, Collection<UUID> serviceIds, int limit);
}
