package io.cartogra.registry.application.usecase;

import io.cartogra.common.api.PageResult;
import io.cartogra.registry.domain.Service;

import java.util.UUID;

public interface DetectOrphansUseCase {
    PageResult<Service> execute(UUID tenantId, int limit, int offset);
}
