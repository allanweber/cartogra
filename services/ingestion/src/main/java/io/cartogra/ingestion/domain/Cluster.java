package io.cartogra.ingestion.domain;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

public record Cluster(
        UUID id,
        UUID tenantId,
        String name,
        ClusterSource source,
        String apiServerUrl,
        @Nullable String caCertPem,
        @Nullable String saToken,
        boolean skipTlsVerify,
        ClusterStatus status,
        @Nullable String statusMessage,
        Instant createdAt,
        Instant updatedAt,
        @Nullable Instant deletedAt,
        @Nullable String kubeconfigEncrypted
) {
    public boolean isDeleted() {
        return deletedAt != null;
    }
}
