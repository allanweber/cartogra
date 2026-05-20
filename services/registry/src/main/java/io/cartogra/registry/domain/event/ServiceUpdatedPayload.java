package io.cartogra.registry.domain.event;

import java.util.UUID;

public record ServiceUpdatedPayload(
        UUID serviceId,
        UUID tenantId,
        String name,
        String description,
        UUID teamId,
        String repositoryUrl,
        String techStack,
        String healthStatus
) {}
