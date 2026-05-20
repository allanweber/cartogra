package io.cartogra.ingestion.application.port.out;

import java.util.UUID;

public record SyncResultPayload(
        UUID syncJobId,
        UUID connectionId,
        UUID tenantId,
        String status,
        int repositoriesSynced,
        String errorMessage
) {}
