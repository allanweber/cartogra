package io.cartogra.registry.application.usecase;

import io.cartogra.registry.domain.Service;

import java.util.UUID;

public interface FindServiceUseCase {
    Service execute(UUID tenantId, UUID serviceId);
}
