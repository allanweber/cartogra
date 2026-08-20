package io.cartogra.topology.repository;

import java.util.UUID;

public interface ProcessedEventRepository {

    /**
     * Records that this envelope has been applied. Returns {@code true} the first time an
     * (tenantId, eventId) pair is seen, {@code false} on every replay — the caller should
     * skip processing when this returns {@code false}.
     */
    boolean markProcessed(UUID tenantId, UUID eventId);
}
