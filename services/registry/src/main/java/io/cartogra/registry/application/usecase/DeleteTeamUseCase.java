package io.cartogra.registry.application.usecase;

import java.util.UUID;

public interface DeleteTeamUseCase {
    void execute(UUID tenantId, UUID teamId);
}
