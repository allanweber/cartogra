package io.cartogra.gateway.domain;

import java.time.Instant;
import java.util.UUID;

public record RefreshToken(
    UUID id,
    UUID tenantId,
    UUID userId,
    String tokenHash,
    Instant issuedAt,
    Instant expiresAt,
    Instant revokedAt,
    Instant createdAt,
    Instant deletedAt
) {}
