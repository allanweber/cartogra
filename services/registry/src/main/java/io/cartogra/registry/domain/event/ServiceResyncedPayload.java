package io.cartogra.registry.domain.event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ServiceResyncedPayload(
        UUID tenantId,
        String k8sCluster,
        Instant observedAt,
        List<PresentService> services
) {
    public ServiceResyncedPayload {
        services = services == null ? List.of() : List.copyOf(services);
    }

    public record PresentService(String namespace, String name) {}
}
