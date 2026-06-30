package io.cartogra.registry.domain;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record Service(
        UUID id,
        UUID tenantId,
        String name,
        @Nullable String description,
        @Nullable UUID teamId,
        @Nullable String repositoryUrl,
        @Nullable List<String> techStack,
        @Nullable String metadata,
        ServiceHealthStatus healthStatus,
        @Nullable Instant lastDeployedAt,
        Instant createdAt,
        Instant updatedAt,
        @Nullable Instant deletedAt,
        // discovery fields — null for manually-created services
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
) {
    public boolean isOrphaned() {
        return teamId == null;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }
}
