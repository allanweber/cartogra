package io.cartogra.registry.application.dto;

import java.util.UUID;

public record UpdateServiceCommand(
        UUID tenantId,
        UUID serviceId,
        String name,
        String description,
        String repositoryUrl,
        String techStack,
        String metadata,
        String healthStatus,
        UUID requestedBy
) {}
