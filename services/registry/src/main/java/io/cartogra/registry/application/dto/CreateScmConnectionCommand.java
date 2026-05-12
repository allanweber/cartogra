package io.cartogra.registry.application.dto;

import java.util.UUID;

public record CreateScmConnectionCommand(
        UUID tenantId,
        String provider,
        String config
) {}
