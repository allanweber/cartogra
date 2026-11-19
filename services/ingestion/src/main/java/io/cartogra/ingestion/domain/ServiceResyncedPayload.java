package io.cartogra.ingestion.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The full set of tenant Services in one cluster at {@code observedAt}. The registry removes the cluster from
 * each row of that cluster that is not in the set and did not change after {@code observedAt}.
 */
public record ServiceResyncedPayload(
        UUID tenantId,
        String k8sCluster,
        Instant observedAt,
        List<PresentService> services
) {
    public ServiceResyncedPayload {
        services = List.copyOf(services);
    }
}
