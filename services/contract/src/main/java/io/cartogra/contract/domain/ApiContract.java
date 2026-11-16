package io.cartogra.contract.domain;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

public record ApiContract(
        UUID id,
        UUID tenantId,
        UUID serviceId,
        String specType,
        Instant createdAt,
        Instant updatedAt,
        @Nullable Instant deletedAt
) {
    public static final String OPENAPI = "openapi";
}
