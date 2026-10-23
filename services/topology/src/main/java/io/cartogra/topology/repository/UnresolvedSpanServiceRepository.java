package io.cartogra.topology.repository;

import io.cartogra.topology.domain.UnresolvedSpanService;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UnresolvedSpanServiceRepository {

    /**
     * Bumps {@code seen_count}/{@code last_seen_at} on the existing row (matched on lower(name)), or
     * inserts a new one unless the tenant already has {@code maxPerTenant} live rows or the name was
     * dismissed — span data is untrusted, so the number of distinct names is capped, and a dismissal
     * is never re-raised.
     */
    void record(UUID tenantId, String name, Instant seenAt, int maxPerTenant);

    List<UnresolvedSpanService> findActive(UUID tenantId, int limit, int offset);

    long countActive(UUID tenantId);

    Optional<UnresolvedSpanService> findById(UUID tenantId, UUID id);

    boolean dismiss(UUID tenantId, UUID id, @Nullable UUID dismissedBy, Instant dismissedAt);

    void markRegistered(UUID tenantId, String name, Instant registeredAt);
}
