package io.cartogra.ingestion.domain;

import java.util.UUID;

public record ServiceRemovedPayload(
        UUID tenantId,
        String k8sCluster,
        String k8sNamespace,
        String name
) {}
