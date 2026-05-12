package io.cartogra.registry.application.usecase;

import io.cartogra.registry.domain.ScmConnection;

import java.util.UUID;

public interface FindScmConnectionUseCase {
    ScmConnection execute(UUID tenantId, UUID connectionId);
}
