package io.cartogra.registry.application.dto;

import java.util.UUID;

public record UpdateTeamCommand(
        UUID tenantId,
        UUID teamId,
        String name
) {}
