package io.cartogra.registry.domain.event;

import java.util.UUID;

public record ServiceRemovedPayload(
        UUID tenantId,
        String k8sCluster,
        String k8sNamespace,
        String name
) {}
