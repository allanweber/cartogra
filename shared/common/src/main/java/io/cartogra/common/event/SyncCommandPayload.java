package io.cartogra.common.event;

import java.util.UUID;

public record SyncCommandPayload(
        UUID connectionId,
        UUID tenantId,
        String providerType
) {}
