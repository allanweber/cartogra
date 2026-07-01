package io.cartogra.registry.api.dto;

import io.cartogra.registry.domain.Service;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ServiceResponse(
        UUID id,
        UUID tenantId,
        String name,
        @Nullable String description,
        @Nullable UUID teamId,
        @Nullable String repositoryUrl,
        @Nullable List<String> techStack,
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
        @Nullable Instant healthCheckedAt,
        @Nullable String tier,
        @Nullable List<String> tags,
        @Nullable BigDecimal slaTarget,
        @Nullable String documentationUrl,
        @Nullable String runbookUrl
) {
    public static ServiceResponse from(Service s) {
        return new ServiceResponse(
                s.id(), s.tenantId(), s.name(), s.description(), s.teamId(), s.repositoryUrl(),
                s.techStack(), s.metadata(), s.healthStatus().name(), s.lastDeployedAt(),
                s.createdAt(), s.updatedAt(), s.externalId(), s.connectionId(), s.source(),
                s.repositoryRef(), s.k8sCluster(), s.k8sNamespace(), s.k8sDeployment(),
                s.healthEndpoint(), s.lastCommitAt(), s.lastCommitSha(), s.healthCheckedAt(),
                s.tier() != null ? s.tier().name() : null,
                s.tags(), s.slaTarget(), s.documentationUrl(), s.runbookUrl()
        );
    }
}
