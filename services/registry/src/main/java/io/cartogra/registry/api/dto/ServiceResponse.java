package io.cartogra.registry.api.dto;

import java.time.Instant;
import java.util.UUID;

public record ServiceResponse(
        UUID id,
        UUID tenantId,
        String name,
        String description,
        UUID teamId,
        String repositoryUrl,
        String techStack,
        String metadata,
        String healthStatus,
        Instant lastDeployedAt,
        Instant createdAt,
        Instant updatedAt
) {}
