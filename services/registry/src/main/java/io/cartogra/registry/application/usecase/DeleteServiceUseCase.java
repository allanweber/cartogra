package io.cartogra.registry.application.usecase;

import java.util.UUID;

public interface DeleteServiceUseCase {
    void execute(UUID tenantId, UUID serviceId, UUID requestedBy);
}
