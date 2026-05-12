package io.cartogra.registry.application.repository;

import io.cartogra.registry.domain.ServiceSnapshot;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ServiceHistoryRepository {

    void save(ServiceSnapshot snapshot);

    List<ServiceSnapshot> findByServiceId(UUID tenantId, UUID serviceId, int limit, int offset);

    Optional<ServiceSnapshot> findAtPointInTime(UUID tenantId, UUID serviceId, Instant at);
}
