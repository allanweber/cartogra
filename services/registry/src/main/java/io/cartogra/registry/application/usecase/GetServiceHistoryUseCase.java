package io.cartogra.registry.application.usecase;

import io.cartogra.common.api.PageResult;
import io.cartogra.registry.domain.ServiceSnapshot;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface GetServiceHistoryUseCase {
    PageResult<ServiceSnapshot> execute(UUID tenantId, UUID serviceId, int limit, int offset);
    Optional<ServiceSnapshot> atPointInTime(UUID tenantId, UUID serviceId, Instant at);
}
