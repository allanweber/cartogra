package io.cartogra.ingestion.api.dto;

import io.cartogra.ingestion.domain.Cluster;
import io.cartogra.ingestion.domain.ClusterSource;
import io.cartogra.ingestion.domain.ClusterStatus;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

public record KubernetesClusterResponse(
        UUID id,
        UUID tenantId,
        String name,
        ClusterSource source,
        String apiServerUrl,
        boolean skipTlsVerify,
        ClusterStatus status,
        @Nullable String statusMessage,
        Instant createdAt,
        Instant updatedAt
) {
    public static KubernetesClusterResponse from(Cluster c) {
        return new KubernetesClusterResponse(
                c.id(), c.tenantId(), c.name(), c.source(),
                c.apiServerUrl(), c.skipTlsVerify(),
                c.status(), c.statusMessage(),
                c.createdAt(), c.updatedAt()
        );
    }
}
