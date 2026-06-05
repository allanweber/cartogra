package io.cartogra.registry.application.dto;

import java.util.UUID;

public record CreateServiceCommand(
        UUID tenantId,
        String name,
        String description,
        UUID teamId,
        String repositoryUrl,
        String techStack,
        String metadata,
        UUID requestedBy,
        String healthEndpoint
) {}
