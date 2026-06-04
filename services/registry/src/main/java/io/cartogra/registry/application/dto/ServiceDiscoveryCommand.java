package io.cartogra.registry.application.dto;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ServiceDiscoveryCommand(
        UUID tenantId,
        @Nullable UUID connectionId,
        String source,
        String externalId,
        String name,
        @Nullable String description,
        @Nullable String repositoryUrl,
        @Nullable String repositoryRef,
        @Nullable String k8sCluster,
        @Nullable String k8sNamespace,
        @Nullable String k8sDeployment,
        List<String> techStack,
        String healthStatus,
        @Nullable String healthEndpoint,
        @Nullable Instant lastCommitAt,
        @Nullable String lastCommitSha
) {}
