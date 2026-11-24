package io.cartogra.registry.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A stored API Key. It holds the hash and the display prefix only, never the full key.
 */
public record ApiKey(
        UUID id,
        UUID tenantId,
        String name,
        String keyHash,
        String keyPrefix,
        List<String> scopes,
        Instant expiresAt,
        Instant revokedAt,
        Instant lastUsedAt,
        UUID createdBy,
        Instant createdAt,
        Instant updatedAt
) {
    public ApiKeyStatus statusAt(Instant now) {
        if (revokedAt != null) {
            return ApiKeyStatus.REVOKED;
        }
        if (expiresAt != null && !expiresAt.isAfter(now)) {
            return ApiKeyStatus.EXPIRED;
        }
        return ApiKeyStatus.ACTIVE;
    }
}
