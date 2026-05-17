package io.cartogra.gateway.infrastructure.jdbc;

import io.cartogra.gateway.domain.RefreshToken;

import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository {
    Optional<RefreshToken> findActiveByHash(String tokenHash);
    RefreshToken save(RefreshToken token);
    void revoke(UUID id);
    void revokeAllForUser(UUID userId);
    int deleteExpiredAndRevoked(java.time.Duration retentionPeriod);
}
