package io.cartogra.topology.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Transient buffer of span halves waiting for their CLIENT/SERVER partner; rows are hard-deleted. */
public interface PendingSpanLinkRepository {

    /** The pending CLIENT span whose {@code span_id} equals a SERVER span's {@code parent_span_id}. */
    Optional<PendingSpanLink> findClientPartner(UUID tenantId, String traceId, String parentSpanId);

    /** The pending SERVER span whose {@code parent_span_id} equals a CLIENT span's {@code span_id}. */
    Optional<PendingSpanLink> findServerPartner(UUID tenantId, String traceId, String clientSpanId);

    /** Transaction-scoped advisory lock serializing the partner-lookup/insert join for one tenant. */
    void lockTenant(UUID tenantId);

    void insert(PendingSpanLink link);

    void delete(UUID id);

    /** Deletes pending SERVER spans whose {@code parent_span_id} is one of the given CLIENT spans. */
    int deleteServerPartners(UUID tenantId, Collection<SpanRef> clientSpans);

    /**
     * Inserts one row per endpoint, or pushes an existing row's {@code expires_at} forward when it
     * would move by more than a minute. Messaging rows are never deleted on match, only reaped.
     */
    void upsertMessagingEndpoints(UUID tenantId, Collection<MessagingEndpoint> endpoints, Instant expiresAt);

    /** Live producer and consumer rows for the given destinations. */
    List<MessagingEndpoint> findMessagingEndpoints(UUID tenantId, Collection<String> destinations);

    int deleteExpired(Instant now);
}
