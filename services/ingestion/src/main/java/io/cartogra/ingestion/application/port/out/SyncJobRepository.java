package io.cartogra.ingestion.application.port.out;

import io.cartogra.ingestion.domain.SyncJob;

import java.util.Optional;
import java.util.UUID;

public interface SyncJobRepository {

    SyncJob save(SyncJob job);

    Optional<SyncJob> findById(UUID tenantId, UUID jobId);

    void markRunning(UUID jobId);

    void markCompleted(UUID jobId, int repositoriesSynced);

    void markFailed(UUID jobId, String errorMessage);

    boolean existsRunningForConnection(UUID tenantId, UUID connectionId);

    Optional<SyncJob> findRunningForConnection(UUID tenantId, UUID connectionId);
}
