package io.cartogra.ingestion.domain;

import java.util.Map;
import java.util.UUID;

public record ScmConnectionConfig(
        UUID connectionId,
        UUID tenantId,
        String providerType,
        Map<String, Object> config
) {}
