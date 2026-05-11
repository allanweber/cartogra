package io.cartogra.registry.api.mapper;

import io.cartogra.registry.api.dto.ScmConnectionResponse;
import io.cartogra.registry.domain.ScmConnection;

public final class ScmConnectionMapper {

    private ScmConnectionMapper() {}

    public static ScmConnectionResponse toResponse(ScmConnection c) {
        return new ScmConnectionResponse(
                c.id(), c.tenantId(), c.provider(), c.config(), c.createdAt(), c.updatedAt()
        );
    }
}
