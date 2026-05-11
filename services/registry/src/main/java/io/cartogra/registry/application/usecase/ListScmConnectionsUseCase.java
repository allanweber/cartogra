package io.cartogra.registry.application.usecase;

import io.cartogra.common.api.PageResult;
import io.cartogra.registry.domain.ScmConnection;

import java.util.UUID;

public interface ListScmConnectionsUseCase {
    PageResult<ScmConnection> execute(UUID tenantId, int limit, int offset);
}
