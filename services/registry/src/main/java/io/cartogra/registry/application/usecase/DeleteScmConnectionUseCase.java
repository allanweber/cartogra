package io.cartogra.registry.application.usecase;

import java.util.UUID;

public interface DeleteScmConnectionUseCase {
    void execute(UUID tenantId, UUID connectionId);
}
