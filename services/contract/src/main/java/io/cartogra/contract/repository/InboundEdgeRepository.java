package io.cartogra.contract.repository;

import io.cartogra.contract.domain.Consumer;
import io.cartogra.contract.domain.InboundEdge;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface InboundEdgeRepository {

    /** Inserts or replaces the edge; a removed edge stays removed. */
    void upsert(UUID tenantId, InboundEdge edge, Instant at);

    /** Marks the edge removed, also when contract has not seen it yet, so a later declared event cannot add it. */
    void markRemoved(UUID tenantId, InboundEdge edge, Instant at);

    /**
     * The sources of live edges into {@code targetServiceId} with one of {@code protocols}, one row per source,
     * without self edges and tombstoned services, sorted by name (unnamed last).
     */
    List<Consumer> findConsumers(UUID tenantId, UUID targetServiceId, Collection<String> protocols, int limit,
            int offset);

    long countConsumers(UUID tenantId, UUID targetServiceId, Collection<String> protocols);
}
