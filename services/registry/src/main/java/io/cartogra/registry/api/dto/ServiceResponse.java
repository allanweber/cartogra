package io.cartogra.registry.api.dto;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

public record ServiceResponse(
        UUID id,
        UUID tenantId,
        String name,
        @Nullable String description,
        @Nullable UUID teamId,
        @Nullable String repositoryUrl,
        @Nullable String techStack,
        @Nullable String metadata,
        String healthStatus,
        @Nullable Instant lastDeployedAt,
        Instant createdAt,
        Instant updatedAt,
        @Nullable String externalId,
        @Nullable UUID connectionId,
        @Nullable String source,
        @Nullable String repositoryRef,
        @Nullable String k8sCluster,
        @Nullable String k8sNamespace,
        @Nullable String k8sDeployment,
        @Nullable String healthEndpoint,
        @Nullable Instant lastCommitAt,
        @Nullable String lastCommitSha,
        @Nullable Instant healthCheckedAt
) {}
