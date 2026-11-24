package io.cartogra.registry.api.dto;

import io.cartogra.registry.domain.CreatedApiKey;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CreatedApiKeyResponse(
        UUID id,
        String name,
        String prefix,
        List<String> scopes,
        String status,
        Instant expiresAt,
        Instant lastUsedAt,
        Instant createdAt,
        String key
) {
    public static CreatedApiKeyResponse from(CreatedApiKey created) {
        ApiKeyResponse r = ApiKeyResponse.from(created.apiKey());
        return new CreatedApiKeyResponse(r.id(), r.name(), r.prefix(), r.scopes(), r.status(), r.expiresAt(),
                r.lastUsedAt(), r.createdAt(), created.key());
    }
}
