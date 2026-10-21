package io.cartogra.gateway.domain;

import java.time.Instant;
import java.util.UUID;

/** One row per session: refresh rotates {@code tokenHash} in place and keeps the prior hash in
 *  {@code previousHash} so a replayed token can be detected. */
public record RefreshToken(
    UUID id,
    UUID tenantId,
    UUID userId,
    String tokenHash,
    String previousHash,
    Instant issuedAt,
    Instant expiresAt,
    Instant lastUsedAt,
    Instant revokedAt,
    Instant createdAt,
    Instant deletedAt
) {
    public static RefreshToken issue(UUID tenantId, UUID userId, String tokenHash, Instant expiresAt) {
        return new RefreshToken(null, tenantId, userId, tokenHash, null, null, expiresAt, null, null, null, null);
    }
}
