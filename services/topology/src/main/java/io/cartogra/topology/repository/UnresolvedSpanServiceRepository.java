package io.cartogra.topology.repository;

import java.time.Instant;
import java.util.UUID;

public interface UnresolvedSpanServiceRepository {

    /**
     * Bumps {@code seen_count}/{@code last_seen_at} on the existing row (matched on lower(name)), or
     * inserts a new one unless the tenant already has {@code maxPerTenant} live rows — span data is
     * untrusted, so the number of distinct names is capped.
     */
    void record(UUID tenantId, String name, Instant seenAt, int maxPerTenant);
}
