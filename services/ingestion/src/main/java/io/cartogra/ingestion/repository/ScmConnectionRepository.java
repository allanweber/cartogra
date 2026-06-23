package io.cartogra.ingestion.repository;

import io.cartogra.ingestion.domain.ScmConnection;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ScmConnectionRepository {

    Optional<ScmConnection> findById(UUID tenantId, UUID id);

    List<ScmConnection> findAll(UUID tenantId, int limit, int offset);

    long count(UUID tenantId);

    ScmConnection save(ScmConnection connection);

    void softDelete(UUID tenantId, UUID id);

    /** Scheduler: connections with scheduling enabled and due for a sync. */
    List<ScmConnection> findDue();

    /** Scheduler: advance the sync window after a tick. */
    void updateSyncTimestamps(UUID id, Instant lastSyncAt, Instant nextSyncAt);

    /** Sync feedback: record the outcome of a completed sync job. */
    void updateSyncResult(UUID id, String status, Instant lastSyncAt);
}
