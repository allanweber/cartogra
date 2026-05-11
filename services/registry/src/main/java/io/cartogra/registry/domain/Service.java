package io.cartogra.registry.domain;

import java.time.Instant;
import java.util.UUID;

public record Service(
        UUID id,
        UUID tenantId,
        String name,
        String description,
        UUID teamId,
        String repositoryUrl,
        String techStack,
        String metadata,
        ServiceHealthStatus healthStatus,
        Instant lastDeployedAt,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt
) {
    public boolean isOrphaned() {
        return teamId == null;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }
}
