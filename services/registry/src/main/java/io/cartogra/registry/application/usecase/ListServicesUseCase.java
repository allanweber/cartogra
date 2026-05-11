package io.cartogra.registry.application.usecase;

import io.cartogra.common.api.PageResult;
import io.cartogra.registry.application.dto.ServiceFilter;
import io.cartogra.registry.domain.Service;

import java.util.UUID;

public interface ListServicesUseCase {
    PageResult<Service> execute(UUID tenantId, ServiceFilter filter, int limit, int offset);
}
