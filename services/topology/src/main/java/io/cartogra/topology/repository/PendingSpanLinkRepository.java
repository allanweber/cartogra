package io.cartogra.topology.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Join buffer for span halves (ADR-0029 §5). Every method that reads or parks CLIENT/SERVER halves first takes a
 * transaction-scoped advisory lock for the tenant, so a lookup and the park that follows it can never interleave with
 * another instance handling the other half of the same call. Callers must run inside a transaction.
 */
public interface PendingSpanLinkRepository {

    /** Removes and returns the parked CLIENT span whose {@code span_id} equals a SERVER span's parent. */
    Optional<PendingSpanLink> takeClientPartner(UUID tenantId, String traceId, String parentSpanId);

    /** Removes and returns the parked SERVER span whose {@code parent_span_id} equals a CLIENT span's id. */
    Optional<PendingSpanLink> takeServerPartner(UUID tenantId, String traceId, String clientSpanId);

    /** Parks unmatched halves until their partner arrives or they expire. */
    void park(UUID tenantId, Collection<PendingSpanLink> links);

    /**
     * Deletes parked SERVER spans whose parent is one of the given directly-resolved CLIENT spans. Lock-free: a row
     * inserted concurrently that slips past is only reaped later, never wrongly joined.
     */
    int deleteServerPartners(UUID tenantId, Collection<SpanRef> clientSpans);

    /**
     * Records the batch's producers and consumers (pushing {@code expires_at} forward when it would move by more than
     * a minute; rows are never deleted on match, only reaped) and returns every live endpoint of the batch's
     * destinations, the batch included. Lock-free: two instances racing on opposite sides can miss each other once.
     */
    Set<MessagingEndpoint> joinMessagingEndpoints(UUID tenantId, Collection<MessagingEndpoint> batch,
                                                  Instant expiresAt);

    int deleteExpired(Instant now);
}
