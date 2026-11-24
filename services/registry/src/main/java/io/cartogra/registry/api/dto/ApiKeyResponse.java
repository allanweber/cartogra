package io.cartogra.registry.api.dto;

import io.cartogra.registry.domain.ApiKey;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ApiKeyResponse(
        UUID id,
        String name,
        String prefix,
        List<String> scopes,
        String status,
        Instant expiresAt,
        Instant lastUsedAt,
        Instant createdAt
) {
    public static ApiKeyResponse from(ApiKey k) {
        return new ApiKeyResponse(k.id(), k.name(), k.keyPrefix(), k.scopes(), k.statusAt(Instant.now()).value(),
                k.expiresAt(), k.lastUsedAt(), k.createdAt());
    }
}
