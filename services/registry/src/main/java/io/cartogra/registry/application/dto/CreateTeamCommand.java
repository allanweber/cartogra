package io.cartogra.registry.application.dto;

import java.util.UUID;

public record CreateTeamCommand(
        UUID tenantId,
        String name
) {}
