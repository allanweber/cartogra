package io.cartogra.registry.application.dto;

import java.util.UUID;

public record UpdateScmConnectionCommand(
        UUID tenantId,
        UUID connectionId,
        String provider,
        String config
) {}
