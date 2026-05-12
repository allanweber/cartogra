package io.cartogra.registry.application.dto;

import java.util.UUID;

public record AssignOwnerCommand(
        UUID tenantId,
        UUID serviceId,
        UUID teamId,
        UUID requestedBy
) {}
