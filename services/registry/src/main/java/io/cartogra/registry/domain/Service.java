package io.cartogra.registry.domain;

import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
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
        @Nullable Instant healthCheckedAt,
        // profile fields
        @Nullable ServiceTier tier,
        @Nullable List<String> tags,
        @Nullable BigDecimal slaTarget,
        @Nullable String documentationUrl,
        @Nullable String runbookUrl
) {
    /** A copy owned by {@code newTeamId} (null = orphaned), stamped {@code at}. */
    public Service withTeam(@Nullable UUID newTeamId, Instant at) {
        return new Service(id, tenantId, name, description, newTeamId, repositoryUrl, techStack, metadata,
                healthStatus, lastDeployedAt, createdAt, at, deletedAt, externalId, connectionId, source,
                repositoryRef, k8sCluster, k8sNamespace, k8sDeployment, healthEndpoint, lastCommitAt, lastCommitSha,
                healthCheckedAt, tier, tags, slaTarget, documentationUrl, runbookUrl);
    }

    public boolean isOrphaned() {
        return teamId == null;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }
}
