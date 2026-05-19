package io.cartogra.ingestion.application.port.in;

import java.util.Map;
import java.util.UUID;

public record SyncCommandPayload(
        UUID connectionId,
        UUID tenantId,
        String providerType,
        Map<String, Object> connectionConfig
) {}
