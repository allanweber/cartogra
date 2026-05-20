package io.cartogra.ingestion.application.port.out;

import java.util.Map;
import java.util.UUID;

public record ScmConnectionConfig(
        UUID connectionId,
        UUID tenantId,
        String providerType,
        Map<String, Object> config
) {}
